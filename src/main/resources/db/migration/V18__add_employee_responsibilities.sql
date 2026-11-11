ALTER TABLE employees
    ADD CONSTRAINT uq_employee_structure UNIQUE (employee_id, structure_id);

CREATE TABLE employee_responsibilities (
    employee_id UUID PRIMARY KEY,
    structure_id UUID NOT NULL,
    manager_user_id UUID NOT NULL,
    manager_role VARCHAR(50) NOT NULL DEFAULT 'MANAGER' CHECK (manager_role = 'MANAGER'),

    FOREIGN KEY (employee_id, structure_id)
        REFERENCES employees (employee_id, structure_id) ON DELETE CASCADE,
    FOREIGN KEY (manager_user_id, structure_id, manager_role)
        REFERENCES structure_membership_roles (user_id, structure_id, role) ON DELETE CASCADE
);

CREATE INDEX idx_employee_responsibilities_manager
    ON employee_responsibilities (manager_user_id, structure_id, manager_role);
