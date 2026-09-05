CREATE TABLE deployment_event (
    id UUID PRIMARY KEY,
    deployment_id UUID NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_deployment_event_deployment
        FOREIGN KEY (deployment_id)
        REFERENCES deployment(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_deployment_event_deployment_id
    ON deployment_event(deployment_id);

CREATE INDEX idx_deployment_event_created_at
    ON deployment_event(created_at);
