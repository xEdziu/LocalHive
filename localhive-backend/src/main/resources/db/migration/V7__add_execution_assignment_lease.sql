ALTER TABLE execution_assignments
    ADD COLUMN claimed_at TIMESTAMP(6),
    ADD COLUMN lease_expires_at TIMESTAMP(6),
    ADD COLUMN lease_token_hash VARCHAR(255);

ALTER TABLE execution_assignments
    ADD CONSTRAINT execution_assignments_lease_fields_check CHECK (
        (
            claimed_at IS NULL
            AND lease_expires_at IS NULL
            AND lease_token_hash IS NULL
        )
        OR
        (
            claimed_at IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND lease_token_hash IS NOT NULL
        )
    );
