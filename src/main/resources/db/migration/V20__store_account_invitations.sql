CREATE TABLE account_invitations (
    user_id UUID PRIMARY KEY REFERENCES user_accounts (user_id),
    invitation_id UUID NOT NULL UNIQUE,
    structure_id UUID NOT NULL REFERENCES organizational_structures (structure_id),
    token_digest VARCHAR(64) UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,

    CHECK ((token_digest IS NULL) = (expires_at IS NULL)),
    CHECK (completed_at IS NULL OR token_digest IS NULL)
);

CREATE INDEX idx_account_invitations_expiry
    ON account_invitations (expires_at, invitation_id)
    WHERE expires_at IS NOT NULL;
