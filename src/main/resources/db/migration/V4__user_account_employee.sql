ALTER TABLE user_accounts
    ADD COLUMN employee_id UUID;

ALTER TABLE user_accounts
    ADD CONSTRAINT fk_user_account_employee
    FOREIGN KEY (employee_id)
    REFERENCES employees(employee_id);
