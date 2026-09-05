ALTER TABLE deployment
    ADD COLUMN source_software_version VARCHAR(50);

UPDATE deployment
SET source_software_version = '1.0.0'
WHERE id = 'e7edaa9d-89b4-4bc8-9a0b-70230d505314';

UPDATE deployment
SET source_software_version = '1.1.0'
WHERE id = '01209187-480b-4c0f-a23c-e787dfb76cc1';

UPDATE deployment
SET source_software_version = '1.2.0'
WHERE id = '1c702323-b0d3-4539-8f05-d6e66efa8510';

ALTER TABLE deployment
    ALTER COLUMN source_software_version SET NOT NULL;
