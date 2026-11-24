CREATE TABLE succession_confirmations (
    offer_id UUID NOT NULL REFERENCES succession_offers (offer_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_accounts (user_id),
    session_version BIGINT NOT NULL CHECK (session_version >= 0),
    session_id_hash VARCHAR(64) NOT NULL CHECK (session_id_hash ~ '^[0-9a-f]{64}$'),
    preview_digest VARCHAR(64) NOT NULL CHECK (preview_digest ~ '^[0-9a-f]{64}$'),
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (offer_id, user_id),
    CHECK (expires_at > confirmed_at AND expires_at <= confirmed_at + INTERVAL '5 minutes')
);
