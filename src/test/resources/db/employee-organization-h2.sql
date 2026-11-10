CREATE TABLE organizational_structures (
    structure_id UUID PRIMARY KEY
);

CREATE TABLE organizational_units (
    unit_id UUID PRIMARY KEY,
    unit_name VARCHAR(255) NOT NULL,
    structure_id UUID,
    CONSTRAINT fk_organizational_unit_structure
        FOREIGN KEY (structure_id) REFERENCES organizational_structures(structure_id),
    CONSTRAINT uq_organizational_unit_structure UNIQUE (unit_id, structure_id),
    CONSTRAINT chk_unit_has_structure CHECK (structure_id IS NOT NULL)
);

CREATE TABLE employees (
    employee_id UUID PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    contract_hours DOUBLE PRECISION NOT NULL,
    email VARCHAR(255),
    phone_number VARCHAR(255),
    unit_id UUID,
    structure_id UUID,
    CONSTRAINT fk_employee_organizational_unit
        FOREIGN KEY (unit_id) REFERENCES organizational_units(unit_id),
    CONSTRAINT fk_employee_structure
        FOREIGN KEY (structure_id) REFERENCES organizational_structures(structure_id),
    CONSTRAINT fk_employee_unit_structure
        FOREIGN KEY (unit_id, structure_id) REFERENCES organizational_units(unit_id, structure_id),
    CONSTRAINT chk_employee_unit_has_structure CHECK (unit_id IS NULL OR structure_id IS NOT NULL)
);
