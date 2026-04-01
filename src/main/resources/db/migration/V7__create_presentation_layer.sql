-- ============================================================================
-- V7: Presentation Layer — Module, Feature, Capability hierarchy
-- ============================================================================

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
