CREATE TABLE account_mfa_state (
    user_id UUID PRIMARY KEY REFERENCES user_accounts (user_id),
    encrypted_secret BYTEA,
    last_accepted_step BIGINT NOT NULL,
    CHECK (last_accepted_step >= -1),
    CHECK (encrypted_secret IS NOT NULL OR last_accepted_step = -1)
);

CREATE TABLE account_mfa_recovery_codes (
    user_id UUID NOT NULL REFERENCES account_mfa_state (user_id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, code_hash)
);

CREATE TABLE account_mfa_enrollments (
    user_id UUID PRIMARY KEY REFERENCES user_accounts (user_id),
    enrollment_id UUID NOT NULL UNIQUE,
    encrypted_secret BYTEA NOT NULL,
    session_version BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
