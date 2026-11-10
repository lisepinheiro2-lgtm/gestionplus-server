ALTER TABLE user_accounts
    ADD COLUMN user_last_name VARCHAR(255),
    ADD COLUMN user_first_name VARCHAR(255),
    ADD COLUMN user_email VARCHAR(255),
    ADD COLUMN user_phone_number VARCHAR(255);

UPDATE user_accounts AS account
SET user_last_name = CASE
        WHEN employee.last_name ~ '[^[:space:]]' THEN employee.last_name
    END,
    user_first_name = CASE
        WHEN employee.first_name ~ '[^[:space:]]' THEN employee.first_name
    END,
    user_email = CASE
        WHEN employee.email ~ '[^[:space:]]' THEN employee.email
    END,
    user_phone_number = CASE
        WHEN employee.phone_number ~ '[^[:space:]]' THEN employee.phone_number
    END
FROM employees AS employee
WHERE employee.employee_id = account.employee_id
    AND NOT EXISTS (
        SELECT 1
        FROM user_accounts AS other_account
        WHERE other_account.employee_id = account.employee_id
            AND other_account.user_id <> account.user_id
    );