CREATE TABLE tenant_organizations (
	tenant_id UUID PRIMARY KEY,
	tenant_name VARCHAR(255) NOT NULL CHECK (btrim(tenant_name) <> ''),
	super_admin_user_id UUID NOT NULL UNIQUE, 
	
	FOREIGN KEY (super_admin_user_id)
	    REFERENCES user_accounts(user_id)
);
ALTER TABLE organizational_structures
    ADD COLUMN tenant_id UUID;  
ALTER TABLE organizational_structures
    ADD CONSTRAINT fk_structure_tenant
    FOREIGN KEY (tenant_id)
    REFERENCES tenant_organizations(tenant_id);
ALTER TABLE organizational_structures
    ALTER COLUMN tenant_id SET NOT NULL;