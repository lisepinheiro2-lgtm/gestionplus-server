ALTER TABLE account_activation_identity_challenges
    ADD COLUMN send_confirmed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN send_recovery_at TIMESTAMP WITH TIME ZONE;

UPDATE account_activation_identity_challenges
SET revoked_at = CURRENT_TIMESTAMP
WHERE consumed_at IS NULL AND revoked_at IS NULL;

UPDATE account_activation_identity_challenges
SET send_recovery_at = created_at + INTERVAL '2 minutes';

ALTER TABLE account_activation_identity_challenges
    ALTER COLUMN send_recovery_at SET NOT NULL,
    ADD CONSTRAINT chk_activation_identity_send_recovery
        CHECK (send_recovery_at > created_at),
    ADD CONSTRAINT chk_activation_identity_send_confirmation
        CHECK (send_confirmed_at IS NULL OR
            (send_confirmed_at >= created_at AND send_confirmed_at < send_recovery_at));

CREATE INDEX idx_activation_identity_unconfirmed_send
    ON account_activation_identity_challenges (send_recovery_at, challenge_id)
    WHERE send_confirmed_at IS NULL AND consumed_at IS NULL AND revoked_at IS NULL;
