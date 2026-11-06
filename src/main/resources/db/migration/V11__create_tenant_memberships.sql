CREATE TABLE tenant_memberships (
    user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,

    PRIMARY KEY (user_id, tenant_id),
    
    FOREIGN KEY (user_id)
	    REFERENCES user_accounts(user_id),
	    
	FOREIGN KEY (tenant_id)
	    REFERENCES tenant_organizations(tenant_id)
);