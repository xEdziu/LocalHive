ALTER TABLE work_executions
    ADD COLUMN display_name_snapshot VARCHAR(255) NOT NULL DEFAULT 'Work execution';

UPDATE work_executions we
SET display_name_snapshot = COALESCE(
        (
            SELECT NULLIF(btrim(wi.display_name), '')
            FROM work_instances wi
            WHERE wi.id = we.instance_id
        ),
        NULLIF(btrim(wdv.name), ''),
        NULLIF(btrim(wd.logical_identifier), ''),
        'Work execution'
    )
FROM work_definition_versions wdv
JOIN work_definitions wd ON wd.id = wdv.definition_id
WHERE we.definition_version_id = wdv.id;

ALTER TABLE work_executions
    ADD CONSTRAINT work_executions_display_name_snapshot_check CHECK (length(btrim(display_name_snapshot)) > 0);
