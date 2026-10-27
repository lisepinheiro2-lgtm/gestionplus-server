ALTER TABLE employees
    ADD COLUMN unit_id UUID;

ALTER TABLE employees
    ADD CONSTRAINT fk_employee_organizational_unit
    FOREIGN KEY (unit_id)
    REFERENCES organizational_units(unit_id);
