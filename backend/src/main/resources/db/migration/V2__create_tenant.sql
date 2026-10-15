CREATE TABLE tenant (
    tenant_id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(63) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_slug UNIQUE (slug),
    CONSTRAINT ck_tenant_name_nonblank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_tenant_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_tenant_timezone_nonblank CHECK (length(btrim(timezone)) > 0),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_tenant_version_nonnegative CHECK (version >= 0)
);
