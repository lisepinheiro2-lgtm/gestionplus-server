ALTER TABLE organizational_units
    ADD COLUMN structure_id UUID;

ALTER TABLE employees
    ADD COLUMN structure_id UUID;

ALTER TABLE organizational_units
    ADD CONSTRAINT fk_organizational_unit_structure
        FOREIGN KEY (structure_id) REFERENCES organizational_structures(structure_id),
    ADD CONSTRAINT uq_organizational_unit_structure UNIQUE (unit_id, structure_id);

ALTER TABLE employees
    ADD CONSTRAINT fk_employee_structure
        FOREIGN KEY (structure_id) REFERENCES organizational_structures(structure_id),
    ADD CONSTRAINT fk_employee_unit_structure
        FOREIGN KEY (unit_id, structure_id) REFERENCES organizational_units(unit_id, structure_id);

ALTER TABLE organizational_units
    ADD CONSTRAINT chk_unit_has_structure
        CHECK (structure_id IS NOT NULL) NOT VALID;

ALTER TABLE employees
    ADD CONSTRAINT chk_employee_unit_has_structure
        CHECK (unit_id IS NULL OR structure_id IS NOT NULL) NOT VALID;
