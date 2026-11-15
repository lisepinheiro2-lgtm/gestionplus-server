CREATE TABLE account_activation_identity_challenges (
    challenge_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_accounts (user_id),
    invitation_id UUID NOT NULL,
    phone_number VARCHAR(32) NOT NULL,
    code_digest VARCHAR(128) NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_failed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_activation_identity_attempts
        CHECK (failed_attempts BETWEEN 0 AND 3),

    CONSTRAINT chk_activation_identity_final_state
        CHECK (consumed_at IS NULL OR revoked_at IS NULL),

    CONSTRAINT chk_activation_identity_max_failures
        CHECK (failed_attempts < 3 OR revoked_at IS NOT NULL),

    CONSTRAINT chk_activation_identity_failed_at
        CHECK (
            (failed_attempts = 0 AND last_failed_at IS NULL)
            OR
            (failed_attempts > 0 AND last_failed_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_account_activation_identity_current
    ON account_activation_identity_challenges (user_id, invitation_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX idx_account_activation_identity_history
    ON account_activation_identity_challenges (user_id, created_at DESC);