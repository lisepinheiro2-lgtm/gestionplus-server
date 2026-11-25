ALTER TABLE succession_offers
    ALTER COLUMN preview_digest DROP NOT NULL,
    ADD COLUMN current_revision BIGINT,
    ADD COLUMN closure_reason VARCHAR(32),
    ADD COLUMN closed_at TIMESTAMP WITH TIME ZONE;

UPDATE succession_offers
SET status = 'CANCELLED', closure_reason = 'LEGACY_INCOMPATIBLE', closed_at = CURRENT_TIMESTAMP
WHERE status IN ('PENDING', 'ACCEPTED');

ALTER TABLE succession_offers
    DROP CONSTRAINT succession_offers_status_check,
    ADD CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELLED', 'COMPLETED', 'EXPIRED')),
    ADD CHECK (
        (current_revision IS NULL AND preview_digest IS NOT NULL AND status IN ('CANCELLED', 'COMPLETED'))
        OR (current_revision IS NOT NULL AND current_revision > 0 AND preview_digest IS NULL
            AND expires_at = created_at + INTERVAL '24 hours')
    ),
    ADD CHECK (
        (current_revision IS NULL AND (
            (closure_reason IS NULL AND closed_at IS NULL)
            OR (status = 'CANCELLED' AND closure_reason IS NOT DISTINCT FROM 'LEGACY_INCOMPATIBLE' AND closed_at IS NOT NULL)
        ))
        OR (current_revision IS NOT NULL AND (
            (status IN ('PENDING', 'ACCEPTED') AND closure_reason IS NULL AND closed_at IS NULL)
            OR (closure_reason IS NOT NULL AND closed_at IS NOT NULL AND (
                (status = 'EXPIRED' AND closure_reason = 'EXPIRATION' AND closed_at = expires_at)
                OR (closed_at >= created_at AND closed_at < expires_at AND (
                    (status = 'COMPLETED' AND closure_reason = 'TRANSFERRED')
                    OR (status = 'CANCELLED' AND closure_reason IN
                        ('CANCELLED_BY_OUTGOING', 'WITHDRAWN_BY_SUCCESSOR', 'ELIGIBILITY_LOST'))
                ))
            ))
        ))
    );

CREATE UNIQUE INDEX uq_succession_open_organization
    ON succession_offers (tenant_id) WHERE status IN ('PENDING', 'ACCEPTED');
CREATE INDEX idx_succession_expiry
    ON succession_offers (expires_at, offer_id) WHERE status IN ('PENDING', 'ACCEPTED');
CREATE INDEX idx_succession_outgoing ON succession_offers (outgoing_user_id);
CREATE INDEX idx_succession_successor ON succession_offers (successor_user_id);

CREATE TABLE succession_revisions (
    offer_id UUID NOT NULL REFERENCES succession_offers (offer_id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    preview JSONB NOT NULL,
    preview_format SMALLINT NOT NULL DEFAULT 1 CHECK (preview_format = 1),
    preview_digest VARCHAR(64) NOT NULL CHECK (preview_digest ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (offer_id, revision),
    UNIQUE (offer_id, revision, preview_digest)
);

ALTER TABLE succession_offers ADD CONSTRAINT fk_succession_current_revision
    FOREIGN KEY (offer_id, current_revision) REFERENCES succession_revisions (offer_id, revision)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE succession_consents (
    offer_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    user_id UUID NOT NULL REFERENCES user_accounts (user_id),
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (offer_id, revision, user_id),
    FOREIGN KEY (offer_id, revision) REFERENCES succession_revisions (offer_id, revision)
);

ALTER TABLE succession_confirmations RENAME TO succession_legacy_confirmations;
ALTER TABLE succession_legacy_confirmations RENAME CONSTRAINT succession_confirmations_pkey TO succession_legacy_confirmations_pkey;

CREATE TABLE succession_confirmations (
    offer_id UUID NOT NULL,
    user_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    session_version BIGINT NOT NULL CHECK (session_version >= 0),
    session_id_hash VARCHAR(64) NOT NULL CHECK (session_id_hash ~ '^[0-9a-f]{64}$'),
    preview_digest VARCHAR(64) NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (offer_id, user_id),
    FOREIGN KEY (offer_id, revision, user_id) REFERENCES succession_consents (offer_id, revision, user_id),
    FOREIGN KEY (offer_id, revision, preview_digest) REFERENCES succession_revisions (offer_id, revision, preview_digest),
    CHECK (expires_at > confirmed_at AND expires_at <= confirmed_at + INTERVAL '5 minutes')
);

DELETE FROM privileged_confirmation_proofs WHERE action = 'SUPER_ADMIN_SUCCESSION_CREATE';

CREATE FUNCTION protect_succession_offer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.current_revision IS DISTINCT FROM 1 OR NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'A succession offer must start at revision one' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' OR OLD.status NOT IN ('PENDING', 'ACCEPTED') THEN
        RAISE EXCEPTION 'Succession offer history is immutable' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.offer_id, NEW.tenant_id, NEW.outgoing_user_id, NEW.successor_user_id,
           NEW.created_at, NEW.expires_at, NEW.preview_digest)
        IS DISTINCT FROM ROW(OLD.offer_id, OLD.tenant_id, OLD.outgoing_user_id, OLD.successor_user_id,
                             OLD.created_at, OLD.expires_at, OLD.preview_digest) THEN
        RAISE EXCEPTION 'Succession offer identity and initial deadline are immutable' USING ERRCODE = '23514';
    END IF;

    IF NEW.current_revision IS DISTINCT FROM OLD.current_revision THEN
        IF NEW.status <> 'PENDING' OR NEW.current_revision IS DISTINCT FROM OLD.current_revision + 1 THEN
            RAISE EXCEPTION 'Succession revisions must advance one at a time' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.admin_roles_to_transfer IS DISTINCT FROM OLD.admin_roles_to_transfer OR
                (NEW.status = 'PENDING' AND OLD.status = 'ACCEPTED') THEN
            RAISE EXCEPTION 'A new revision is required' USING ERRCODE = '23514';
        END IF;
        IF NEW.status = 'ACCEPTED' AND OLD.status = 'PENDING' AND
            (SELECT count(*) FROM succession_consents
                WHERE offer_id = NEW.offer_id AND revision = NEW.current_revision) <> 2 THEN
            RAISE EXCEPTION 'Two current consents are required' USING ERRCODE = '23514';
        END IF;
        IF NEW.status = 'COMPLETED' AND (OLD.status <> 'ACCEPTED' OR
            (SELECT count(*) FROM succession_confirmations
             WHERE offer_id = NEW.offer_id AND revision = NEW.current_revision
                 AND confirmed_at <= NEW.closed_at AND expires_at > NEW.closed_at) <> 2) THEN
            RAISE EXCEPTION 'Two valid confirmations for an accepted offer are required' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_succession_offer BEFORE INSERT OR UPDATE OR DELETE ON succession_offers
FOR EACH ROW EXECUTE FUNCTION protect_succession_offer();

CREATE FUNCTION remove_obsolete_succession_confirmations() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.current_revision <> OLD.current_revision OR NEW.status NOT IN ('PENDING', 'ACCEPTED') THEN
        DELETE FROM succession_confirmations WHERE offer_id = NEW.offer_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clear_obsolete_succession_confirmations AFTER UPDATE ON succession_offers
FOR EACH ROW EXECUTE FUNCTION remove_obsolete_succession_confirmations();

CREATE FUNCTION protect_succession_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Succession revision and consent history is immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER guard_succession_revision_history BEFORE UPDATE OR DELETE ON succession_revisions
FOR EACH ROW EXECUTE FUNCTION protect_succession_history();
CREATE TRIGGER guard_succession_consent_history BEFORE UPDATE OR DELETE ON succession_consents
FOR EACH ROW EXECUTE FUNCTION protect_succession_history();

CREATE FUNCTION check_succession_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    offer succession_offers%ROWTYPE;
    selected_roles UUID[];
    previous_created_at TIMESTAMP WITH TIME ZONE;
    previous_digest VARCHAR(64);
BEGIN
    SELECT * INTO STRICT offer FROM succession_offers WHERE offer_id = NEW.offer_id FOR UPDATE;
    SELECT ARRAY(SELECT jsonb_array_elements_text(NEW.preview->'adminRolesToTransfer')::uuid)
        INTO selected_roles;
    SELECT created_at, preview_digest INTO previous_created_at, previous_digest FROM succession_revisions
        WHERE offer_id = NEW.offer_id AND revision = NEW.revision - 1;

    IF offer.status <> 'PENDING' OR NEW.revision IS DISTINCT FROM offer.current_revision
        OR (NEW.preview->>'tenantID')::uuid IS DISTINCT FROM offer.tenant_id
        OR (NEW.preview->'outgoing'->>'userID')::uuid IS DISTINCT FROM offer.outgoing_user_id
        OR (NEW.preview->'successor'->>'userID')::uuid IS DISTINCT FROM offer.successor_user_id
        OR selected_roles IS DISTINCT FROM offer.admin_roles_to_transfer
        OR NEW.created_at < offer.created_at OR NEW.created_at >= offer.expires_at
        OR (NEW.revision = 1 AND NEW.created_at <> offer.created_at)
        OR (NEW.revision > 1 AND (previous_created_at IS NULL OR NEW.created_at < previous_created_at OR NEW.preview_digest = previous_digest)) THEN
        RAISE EXCEPTION 'The revision does not match the succession offer' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_succession_revision BEFORE INSERT ON succession_revisions
FOR EACH ROW EXECUTE FUNCTION check_succession_revision();

CREATE FUNCTION check_succession_participant_action() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    offer succession_offers%ROWTYPE;
    recorded_at TIMESTAMP WITH TIME ZONE;
    revision_created_at TIMESTAMP WITH TIME ZONE;
    consent_at TIMESTAMP WITH TIME ZONE;
BEGIN
    SELECT * INTO STRICT offer FROM succession_offers WHERE offer_id = NEW.offer_id FOR UPDATE;
    IF TG_TABLE_NAME = 'succession_consents' THEN
        recorded_at := NEW.accepted_at;
    ELSE
        recorded_at := NEW.confirmed_at;
        SELECT accepted_at INTO consent_at FROM succession_consents
            WHERE offer_id = NEW.offer_id AND revision = NEW.revision AND user_id = NEW.user_id;
        IF consent_at IS NULL OR consent_at > recorded_at THEN
            RAISE EXCEPTION 'Confirmation requires current consent' USING ERRCODE = '23514';
        END IF;
    END IF;

    SELECT created_at INTO revision_created_at FROM succession_revisions
        WHERE offer_id = NEW.offer_id AND revision = NEW.revision;
    IF offer.status NOT IN ('PENDING', 'ACCEPTED') OR NEW.revision IS DISTINCT FROM offer.current_revision
        OR NEW.user_id NOT IN (offer.outgoing_user_id, offer.successor_user_id)
        OR revision_created_at IS NULL OR recorded_at < revision_created_at OR recorded_at >= offer.expires_at THEN
        RAISE EXCEPTION 'Stale or unrelated succession participant action' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_succession_consent BEFORE INSERT ON succession_consents
FOR EACH ROW EXECUTE FUNCTION check_succession_participant_action();
CREATE TRIGGER guard_succession_confirmation BEFORE INSERT OR UPDATE ON succession_confirmations
FOR EACH ROW EXECUTE FUNCTION check_succession_participant_action();
