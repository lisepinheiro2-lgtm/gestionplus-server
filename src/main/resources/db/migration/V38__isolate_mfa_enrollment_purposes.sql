ALTER TABLE account_mfa_enrollments
    ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'INITIAL',
    ADD COLUMN session_id_hash VARCHAR(64);

UPDATE account_mfa_enrollments enrollment
SET purpose = 'RECOVERY'
FROM account_mfa_state factor
WHERE factor.user_id = enrollment.user_id AND factor.encrypted_secret IS NULL;

ALTER TABLE account_mfa_enrollments
    ALTER COLUMN purpose DROP DEFAULT,
    DROP CONSTRAINT account_mfa_enrollments_pkey,
    ADD PRIMARY KEY (user_id, purpose),
    ADD CONSTRAINT chk_mfa_enrollment_binding CHECK (
        (purpose IN ('INITIAL', 'RECOVERY') AND session_id_hash IS NULL)
        OR (purpose = 'VOLUNTARY' AND session_id_hash IS NOT NULL AND session_id_hash ~ '^[0-9a-f]{64}$')
    );
