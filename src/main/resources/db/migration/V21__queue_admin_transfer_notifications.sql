ALTER TABLE account_deactivation_notifications
    ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'ACCOUNT_DEACTIVATION',
    ADD CONSTRAINT chk_account_notification_kind
        CHECK (kind IN ('ACCOUNT_DEACTIVATION', 'ADMIN_REMOVED', 'ADMIN_GRANTED'));
