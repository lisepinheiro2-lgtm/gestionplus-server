ALTER TABLE account_deactivation_notifications
    ADD COLUMN attempts INT NOT NULL DEFAULT 0;