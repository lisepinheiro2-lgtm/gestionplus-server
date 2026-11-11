ALTER TABLE user_accounts
    ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;

INSERT INTO tenant_memberships (user_id, tenant_id)
SELECT super_admin_user_id, tenant_id
FROM tenant_organizations
ON CONFLICT (user_id, tenant_id) DO NOTHING;

ALTER TABLE organizational_structures
    ADD CONSTRAINT uq_structure_tenant UNIQUE (structure_id, tenant_id);

ALTER TABLE structure_memberships
    ADD COLUMN tenant_id UUID;

UPDATE structure_memberships AS membership
SET tenant_id = structure.tenant_id
FROM organizational_structures AS structure
WHERE membership.structure_id = structure.structure_id;

ALTER TABLE structure_memberships
    ALTER COLUMN tenant_id SET NOT NULL,
    ADD CONSTRAINT fk_membership_structure_tenant
        FOREIGN KEY (structure_id, tenant_id)
        REFERENCES organizational_structures (structure_id, tenant_id),
    ADD CONSTRAINT fk_membership_user_tenant
        FOREIGN KEY (user_id, tenant_id)
        REFERENCES tenant_memberships (user_id, tenant_id) NOT VALID;
