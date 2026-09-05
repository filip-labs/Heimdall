CREATE TABLE rollout (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    failure_threshold_percent NUMERIC(5,2) NOT NULL,
    total_vehicles INTEGER NOT NULL,
    current_stage_index INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_rollout_release
        FOREIGN KEY (release_id)
        REFERENCES software_release(id)
);

CREATE INDEX idx_rollout_status
    ON rollout(status);

CREATE TABLE rollout_stage (
    id UUID PRIMARY KEY,
    rollout_id UUID NOT NULL,
    stage_index INTEGER NOT NULL,
    target_percentage INTEGER NOT NULL,
    target_vehicle_count INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_rollout_stage_rollout
        FOREIGN KEY (rollout_id)
        REFERENCES rollout(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_rollout_stage_index
        UNIQUE (rollout_id, stage_index)
);

CREATE INDEX idx_rollout_stage_rollout_id
    ON rollout_stage(rollout_id);

CREATE TABLE rollout_target (
    rollout_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    target_ordinal INTEGER NOT NULL,

    PRIMARY KEY (rollout_id, vehicle_id),

    CONSTRAINT fk_rollout_target_rollout
        FOREIGN KEY (rollout_id)
        REFERENCES rollout(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_rollout_target_vehicle
        FOREIGN KEY (vehicle_id)
        REFERENCES vehicle(id),

    CONSTRAINT uq_rollout_target_ordinal
        UNIQUE (rollout_id, target_ordinal)
);

CREATE INDEX idx_rollout_target_rollout_id
    ON rollout_target(rollout_id);

ALTER TABLE deployment
ADD COLUMN rollout_stage_id UUID;

ALTER TABLE deployment
ADD CONSTRAINT fk_deployment_rollout_stage
FOREIGN KEY (rollout_stage_id)
REFERENCES rollout_stage(id);

CREATE INDEX idx_deployment_rollout_stage_id
    ON deployment(rollout_stage_id);
