CREATE TABLE account_invitation_deliveries (
    request_id UUID PRIMARY KEY,
    invitation_id UUID NOT NULL UNIQUE,
    previous_invitation_id UUID,
    user_id UUID NOT NULL REFERENCES user_accounts (user_id),
    structure_id UUID NOT NULL REFERENCES organizational_structures (structure_id),
    actor_id UUID NOT NULL REFERENCES user_accounts (user_id),
    actor_version BIGINT NOT NULL CHECK (actor_version >= 0),
    target_version BIGINT NOT NULL CHECK (target_version >= 0),
    email VARCHAR(255) NOT NULL,
    action VARCHAR(50) NOT NULL CHECK (action IN ('INVITATION_CREATE', 'INVITATION_RESEND', 'INVITATION_REPLACE')),
    reintegration BOOLEAN NOT NULL,
    token_digest VARCHAR(64) NOT NULL,
    prepared_roles JSONB NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SENT', 'CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX uq_pending_account_invitation_delivery
    ON account_invitation_deliveries (user_id) WHERE state = 'PENDING';

CREATE INDEX idx_account_invitation_deliveries_due
    ON account_invitation_deliveries (next_attempt_at, request_id) WHERE state = 'PENDING';
