CREATE TABLE audit_events(
    event_id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_user_id UUID,
    action VARCHAR(100) NOT NULL,
    result VARCHAR(10) NOT NULL,
    ip_address VARCHAR(45)
);
CREATE TABLE audit_targets(
    event_id UUID NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id UUID NOT NULL,

    PRIMARY KEY (target_id, target_type, event_id),
  
    FOREIGN KEY (event_id)
        REFERENCES audit_events(event_id)
);
