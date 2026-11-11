DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM structure_memberships membership
        LEFT JOIN tenant_memberships tenant
            ON tenant.user_id = membership.user_id AND tenant.tenant_id = membership.tenant_id
        WHERE tenant.user_id IS NULL
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Des rattachements historiques nécessitent une vérification des appartenances entreprise.';
    END IF;
END
$$;

ALTER TABLE structure_memberships
    VALIDATE CONSTRAINT fk_membership_structure_tenant;

ALTER TABLE structure_memberships
    VALIDATE CONSTRAINT fk_membership_user_tenant;
