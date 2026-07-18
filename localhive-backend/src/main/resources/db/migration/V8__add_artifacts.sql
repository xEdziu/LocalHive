CREATE TABLE artifacts (
    id UUID NOT NULL,
    kind VARCHAR(50) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(255),
    CONSTRAINT artifacts_pkey PRIMARY KEY (id),
    CONSTRAINT artifacts_kind_check CHECK (kind IN ('WORKSPACE_PACKAGE')),
    CONSTRAINT artifacts_kind_not_blank_check CHECK (length(btrim(kind)) > 0),
    CONSTRAINT artifacts_original_filename_not_blank_check CHECK (length(btrim(original_filename)) > 0),
    CONSTRAINT artifacts_content_type_not_blank_check CHECK (content_type IS NULL OR length(btrim(content_type)) > 0),
    CONSTRAINT artifacts_size_bytes_check CHECK (size_bytes >= 0),
    CONSTRAINT artifacts_sha256_length_check CHECK (length(sha256) = 64),
    CONSTRAINT artifacts_storage_path_not_blank_check CHECK (length(btrim(storage_path)) > 0)
);

CREATE INDEX idx_artifacts_kind ON artifacts(kind);
CREATE INDEX idx_artifacts_created_at ON artifacts(created_at);
CREATE INDEX idx_artifacts_sha256 ON artifacts(sha256);
