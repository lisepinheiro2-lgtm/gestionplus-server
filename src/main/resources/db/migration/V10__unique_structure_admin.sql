CREATE UNIQUE INDEX uq_structure_admin
ON structure_membership_roles (structure_id)
WHERE role = 'ADMIN';
