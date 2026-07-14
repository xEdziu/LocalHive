ALTER TABLE workers
    ADD COLUMN approval_status VARCHAR(255),
    ADD COLUMN connection_status VARCHAR(255),
    ADD COLUMN availability_status VARCHAR(255);

UPDATE workers
SET approval_status = CASE status
        WHEN 'PENDING' THEN 'PENDING'
        ELSE 'APPROVED'
    END,
    connection_status = CASE status
        WHEN 'ACTIVE' THEN 'ONLINE'
        WHEN 'PAUSED' THEN 'ONLINE'
        ELSE 'OFFLINE'
    END,
    availability_status = CASE status
        WHEN 'PAUSED' THEN 'PAUSED'
        ELSE 'AVAILABLE'
    END;

ALTER TABLE workers
    ALTER COLUMN approval_status SET NOT NULL,
    ALTER COLUMN connection_status SET NOT NULL,
    ALTER COLUMN availability_status SET NOT NULL;

ALTER TABLE workers
    ADD CONSTRAINT workers_approval_status_check CHECK (approval_status IN ('PENDING', 'APPROVED')),
    ADD CONSTRAINT workers_connection_status_check CHECK (connection_status IN ('ONLINE', 'OFFLINE')),
    ADD CONSTRAINT workers_availability_status_check CHECK (availability_status IN ('AVAILABLE', 'PAUSED'));

ALTER TABLE workers
    DROP CONSTRAINT IF EXISTS workers_status_check,
    DROP COLUMN status;
