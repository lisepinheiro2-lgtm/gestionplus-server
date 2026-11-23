CREATE TABLE succession_offers (
    offer_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant_organizations (tenant_id),
    outgoing_user_id UUID NOT NULL REFERENCES user_accounts (user_id),
    successor_user_id UUID NOT NULL REFERENCES user_accounts (user_id),
    admin_roles_to_transfer UUID[] NOT NULL,
    preview_digest VARCHAR(64) NOT NULL CHECK (preview_digest ~ '^[0-9a-f]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELLED', 'COMPLETED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (outgoing_user_id <> successor_user_id),
    CHECK (created_at < expires_at),
    CHECK (array_position(admin_roles_to_transfer, NULL) IS NULL)
);

CREATE INDEX idx_succession_offers_active_tenant
    ON succession_offers (tenant_id, expires_at)
    WHERE status IN ('PENDING', 'ACCEPTED');
