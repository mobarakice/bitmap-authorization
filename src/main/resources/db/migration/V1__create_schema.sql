-- =============================================================================
-- V1: Core Schema for Redis-Based Authorization Service
-- =============================================================================

-- Roles table
CREATE TABLE roles (
    id          SERIAL          PRIMARY KEY,
    name        VARCHAR(100)    NOT NULL UNIQUE,
    description VARCHAR(500),
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
    role_id         INTEGER         NOT NULL REFERENCES roles(id),
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
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

-- User Allow Permissions (extra permissions beyond the role)
CREATE TABLE user_allow_permissions (
    user_id         BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission_id   INTEGER NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, permission_id)
);

CREATE INDEX idx_user_allow_user ON user_allow_permissions(user_id);

-- User Deny Permissions (permissions revoked from the role)
CREATE TABLE user_deny_permissions (
    user_id         BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission_id   INTEGER NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, permission_id)
);

CREATE INDEX idx_user_deny_user ON user_deny_permissions(user_id);
