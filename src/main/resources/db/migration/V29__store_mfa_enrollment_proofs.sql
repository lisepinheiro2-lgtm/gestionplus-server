CREATE TABLE account_mfa_enrollment_proofs (
	user_id UUID PRIMARY KEY REFERENCES user_accounts (user_id) ON DELETE CASCADE,
	proof_hash VARCHAR(64) NOT NULL,
	session_version BIGINT NOT NULL,
	expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
	CONSTRAINT chk_mfa_enrollment_proof_hash CHECK (proof_hash ~ '^[0-9a-f]{64}$'),
	CONSTRAINT chk_mfa_enrollment_proof_version CHECK (session_version >= 0)
);
