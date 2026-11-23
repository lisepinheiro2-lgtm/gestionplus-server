CREATE TABLE privileged_confirmation_proofs (
    user_id UUID NOT NULL REFERENCES user_accounts (user_id) ON DELETE CASCADE,
    action VARCHAR(40) NOT NULL,
    session_version BIGINT NOT NULL CHECK (session_version >= 0),
    preview_digest VARCHAR(64) NOT NULL CHECK (preview_digest ~ '^[0-9a-f]{64}$'),
    proof_hash VARCHAR(64) NOT NULL UNIQUE CHECK (proof_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, action),
    CONSTRAINT chk_privileged_confirmation_action
        CHECK (action IN ('STRUCTURE_ADMIN_TRANSFER', 'SUPER_ADMIN_SUCCESSION_OFFER',
                         'SUPER_ADMIN_SUCCESSION_ACCEPTANCE')),
    CONSTRAINT chk_privileged_confirmation_expiry
        CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '5 minutes')
);
