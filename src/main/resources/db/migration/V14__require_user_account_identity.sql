ALTER TABLE user_accounts
    ALTER COLUMN user_last_name SET NOT NULL,
    ALTER COLUMN user_first_name SET NOT NULL,
    ALTER COLUMN user_email SET NOT NULL,
    ALTER COLUMN user_phone_number SET NOT NULL;
