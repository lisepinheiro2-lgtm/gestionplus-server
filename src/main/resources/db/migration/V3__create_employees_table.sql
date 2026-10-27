CREATE TABLE employees (
    employee_id UUID PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    contract_hours DOUBLE PRECISION NOT NULL,
    email VARCHAR(255),
    phone_number VARCHAR(255)
);
