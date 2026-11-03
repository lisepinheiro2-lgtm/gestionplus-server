CREATE TABLE organizational_structures(
   structure_id UUID PRIMARY KEY,
   structure_name VARCHAR(255) NOT NULL CHECK (btrim(structure_name) <> '')
);
CREATE TABLE structure_memberships(
    structure_id UUID NOT NULL,
    user_id UUID NOT NULL,

PRIMARY KEY (user_id, structure_id),
  
    FOREIGN KEY (structure_id)
        REFERENCES organizational_structures(structure_id),
        
    FOREIGN KEY (user_id)
        REFERENCES user_accounts(user_id)
);
CREATE TABLE structure_membership_roles(
    user_id UUID NOT NULL,
    structure_id UUID NOT NULL,
    role VARCHAR(50) NOT NULL,

PRIMARY KEY (user_id, structure_id, role),

    FOREIGN KEY (user_id, structure_id)
        REFERENCES structure_memberships(user_id, structure_id)
);