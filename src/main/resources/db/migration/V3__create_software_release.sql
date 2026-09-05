CREATE TABLE software_release (
    id UUID PRIMARY KEY,
    version VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(500),
    artifact_url VARCHAR(500) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
