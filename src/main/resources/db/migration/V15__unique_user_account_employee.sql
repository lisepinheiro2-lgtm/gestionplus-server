ALTER TABLE user_accounts
    ADD CONSTRAINT uq_user_account_employee UNIQUE (employee_id);
