ALTER TABLE audit_events
    ADD COLUMN tenant_id UUID;

CREATE INDEX idx_audit_events_tenant_chronology
    ON audit_events (tenant_id, occurred_at, event_id);

CREATE TABLE audit_pending_events (
    event_id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    encrypted_event BYTEA NOT NULL
);

ALTER TABLE account_invitations
    ADD COLUMN reintegration BOOLEAN NOT NULL DEFAULT FALSE;
