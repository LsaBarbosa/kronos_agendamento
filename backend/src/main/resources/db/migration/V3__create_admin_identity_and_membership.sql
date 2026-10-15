CREATE TABLE admin_user (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_issuer VARCHAR(512) NOT NULL,
    identity_subject VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_admin_user_issuer_subject UNIQUE (identity_issuer, identity_subject),
    CONSTRAINT ck_admin_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE admin_tenant_membership (
    user_id UUID PRIMARY KEY REFERENCES admin_user (user_id),
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenant (tenant_id),
    role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_membership_role CHECK (role IN ('TENANT_ADMIN')),
    CONSTRAINT ck_admin_membership_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX ix_admin_membership_tenant_status
    ON admin_tenant_membership (tenant_id, status);
