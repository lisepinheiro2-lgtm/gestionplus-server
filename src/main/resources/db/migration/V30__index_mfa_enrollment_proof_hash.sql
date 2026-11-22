CREATE UNIQUE INDEX uq_mfa_enrollment_proof_hash
	ON account_mfa_enrollment_proofs (proof_hash);
