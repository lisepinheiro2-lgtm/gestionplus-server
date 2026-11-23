ALTER TABLE account_deactivation_notifications
    RENAME COLUMN actor_id TO recipient_user_id;

ALTER TABLE account_deactivation_notifications
    ADD COLUMN actor_user_id UUID,
    ALTER COLUMN structure_id DROP NOT NULL,
    DROP CONSTRAINT chk_account_notification_kind;

ALTER TABLE account_deactivation_notifications
    ADD CONSTRAINT chk_account_notification_kind
        CHECK (kind IN ('ACCOUNT_DEACTIVATION', 'ACCOUNT_DEACTIVATION_TARGET',
                        'ADMIN_REMOVED', 'ADMIN_GRANTED', 'MFA_ENROLLED')),
    ADD CONSTRAINT chk_account_notification_structure
        CHECK ((kind = 'MFA_ENROLLED' AND structure_id IS NULL)
            OR (kind <> 'MFA_ENROLLED' AND structure_id IS NOT NULL)),
    ADD CONSTRAINT chk_account_notification_mfa_identity
        CHECK (kind <> 'MFA_ENROLLED'
            OR (actor_user_id IS NOT NULL AND actor_user_id = target_id
                AND target_id = recipient_user_id));
