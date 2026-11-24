DELETE FROM privileged_confirmation_proofs;

ALTER TABLE privileged_confirmation_proofs
    DROP CONSTRAINT chk_privileged_confirmation_action,
    ADD COLUMN session_id_hash VARCHAR(64) NOT NULL CHECK (session_id_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_privileged_confirmation_action
        CHECK (action IN ('STRUCTURE_ADMIN_TRANSFER', 'SUPER_ADMIN_SUCCESSION_CREATE'));
