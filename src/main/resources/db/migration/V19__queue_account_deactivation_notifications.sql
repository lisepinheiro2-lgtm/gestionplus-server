CREATE TABLE account_deactivation_notifications (
    notification_id UUID PRIMARY KEY,
    actor_id UUID NOT NULL,
    target_id UUID NOT NULL,
    structure_id UUID NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_account_deactivation_notifications_due
    ON account_deactivation_notifications (next_attempt_at, notification_id);
