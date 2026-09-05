CREATE TABLE vehicle (
    id UUID PRIMARY KEY,
    vin VARCHAR(17) NOT NULL UNIQUE,
    software_version VARCHAR(50) NOT NULL,
    connectivity_status VARCHAR(20) NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
