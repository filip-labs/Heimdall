ALTER TABLE deployment
ADD COLUMN rollback_of_deployment_id UUID;

ALTER TABLE deployment
ADD CONSTRAINT fk_deployment_rollback_of_deployment
FOREIGN KEY (rollback_of_deployment_id)
REFERENCES deployment(id);

ALTER TABLE deployment
ADD CONSTRAINT uq_deployment_rollback_of_deployment
UNIQUE (rollback_of_deployment_id);
