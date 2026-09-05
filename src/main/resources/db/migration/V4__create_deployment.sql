CREATE TABLE deployment (
    id UUID PRIMARY KEY,

    vehicle_id UUID NOT NULL,
    release_id UUID NOT NULL,

    status VARCHAR(30) NOT NULL,
    failure_reason VARCHAR(500),

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_deployment_vehicle
        FOREIGN KEY (vehicle_id)
        REFERENCES vehicle(id),

    CONSTRAINT fk_deployment_release
        FOREIGN KEY (release_id)
        REFERENCES software_release(id)
);

CREATE INDEX idx_deployment_vehicle_id
    ON deployment(vehicle_id);

CREATE INDEX idx_deployment_release_id
    ON deployment(release_id);

CREATE INDEX idx_deployment_status
    ON deployment(status);
