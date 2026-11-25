ALTER TABLE account_activation_identity_challenges
    RENAME TO sms_identity_challenges;

ALTER TABLE sms_identity_challenges
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'ACCOUNT_ACTIVATION',
    ADD COLUMN session_id_hash VARCHAR(64),
    ADD COLUMN session_version BIGINT,
    ALTER COLUMN invitation_id DROP NOT NULL;

ALTER TABLE sms_identity_challenges
    ALTER COLUMN purpose DROP DEFAULT,
    ADD CONSTRAINT chk_sms_identity_binding CHECK (
        (purpose = 'ACCOUNT_ACTIVATION'
            AND invitation_id IS NOT NULL AND session_id_hash IS NULL AND session_version IS NULL)
        OR
        (purpose = 'MFA_ENROLLMENT'
            AND invitation_id IS NULL
            AND session_id_hash IS NOT NULL AND session_id_hash ~ '^[0-9a-f]{64}$'
            AND session_version IS NOT NULL AND session_version >= 0
            AND phone_number ~ '^\+[0-9]{1,15}$'
            AND code_digest ~ '^[0-9a-f]{64}$'
            AND expires_at > created_at)
    );

CREATE UNIQUE INDEX uq_sms_identity_current_mfa
    ON sms_identity_challenges (user_id)
    WHERE purpose = 'MFA_ENROLLMENT' AND consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX idx_sms_identity_purpose_history
    ON sms_identity_challenges (user_id, purpose, created_at DESC);
