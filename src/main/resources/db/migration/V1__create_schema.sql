-- =============================================================================
-- V1: Complete Schema for Redis-Based Authorization Service
-- =============================================================================

-- =========================================================================
-- 1. ENFORCEMENT LAYER
-- =========================================================================

-- Roles table
CREATE TABLE roles (
    id          SERIAL          PRIMARY KEY,
    name        VARCHAR(100)    NOT NULL UNIQUE,
    description VARCHAR(500),
    is_active   BOOLEAN         NOT NULL DEFAULT true,
    is_system   BOOLEAN         NOT NULL DEFAULT false,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_roles_name ON roles(name);

-- Permissions table (each permission has a globally unique bit_index)
CREATE TABLE permissions (
    id          SERIAL          PRIMARY KEY,
    resource    VARCHAR(500)    NOT NULL,
    action      VARCHAR(10)     NOT NULL,
    bit_index   INTEGER         NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_permission_resource_action UNIQUE (resource, action)
);

CREATE INDEX idx_permissions_bit_index ON permissions(bit_index);
CREATE INDEX idx_permissions_resource_action ON permissions(resource, action);

-- Users table
CREATE TABLE users (
    id              BIGSERIAL       PRIMARY KEY,
    username        VARCHAR(100)    NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    first_name      VARCHAR(100),
    last_name       VARCHAR(100),
    email           VARCHAR(255),
    mobile_no       VARCHAR(20),
    signcode        VARCHAR(50),
    role_id         INTEGER         REFERENCES roles(id),
    is_active       BOOLEAN         NOT NULL DEFAULT true,
    is_system       BOOLEAN         NOT NULL DEFAULT false,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_mobile_no UNIQUE (mobile_no),
    CONSTRAINT uk_users_signcode UNIQUE (signcode)
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_role_id ON users(role_id);

-- Role-Permission mapping (which permissions a role grants)
CREATE TABLE role_permissions (
    role_id         INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id   INTEGER NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX idx_role_permissions_role ON role_permissions(role_id);

-- User Override Permissions (allow/deny overrides beyond the role)
CREATE TABLE user_override_permissions (
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission_id   INTEGER      NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    override_type   VARCHAR(10)  NOT NULL CHECK (override_type IN ('ALLOW', 'DENY')),
    PRIMARY KEY (user_id, permission_id, override_type)
);

CREATE INDEX idx_user_override_user ON user_override_permissions(user_id);

-- =========================================================================
-- 2. PRESENTATION LAYER
-- =========================================================================

CREATE TABLE modules (
    id SERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    parent_id INT REFERENCES modules(id) ON DELETE CASCADE,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true
);
CREATE INDEX idx_modules_parent ON modules(parent_id);

CREATE TABLE module_features (
    id SERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    module_id INT NOT NULL REFERENCES modules(id) ON DELETE CASCADE,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true
);
CREATE INDEX idx_features_module ON module_features(module_id);

CREATE TABLE capabilities (
    id SERIAL PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    feature_id INT NOT NULL REFERENCES module_features(id) ON DELETE CASCADE,
    description VARCHAR(500)
);
CREATE INDEX idx_capabilities_feature ON capabilities(feature_id);

CREATE TABLE capability_permissions (
    capability_id INT NOT NULL REFERENCES capabilities(id) ON DELETE CASCADE,
    permission_id INT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (capability_id, permission_id)
);
CREATE INDEX idx_cap_perms_capability ON capability_permissions(capability_id);
CREATE INDEX idx_cap_perms_permission ON capability_permissions(permission_id);
