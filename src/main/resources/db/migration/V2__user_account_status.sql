ALTER TABLE user_accounts
    ADD COLUMN status VARCHAR(32);

UPDATE user_accounts
SET status = CASE
    WHEN active = TRUE THEN 'ACTIVE'
    ELSE 'DISABLED'
END;

ALTER TABLE user_accounts
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE user_accounts
    ALTER COLUMN user_name DROP NOT NULL;

ALTER TABLE user_accounts
    DROP COLUMN active;

ALTER TABLE user_accounts
    ADD CONSTRAINT chk_user_account_status
    CHECK (status IN (
        'PENDING_ACTIVATION',
        'ACTIVE',
        'DISABLED'
    ));

ALTER TABLE user_accounts
    ADD CONSTRAINT chk_active_user_name
    CHECK (
        status <> 'ACTIVE'
        OR (
            user_name IS NOT NULL
            AND btrim(user_name) <> ''
        )
    );