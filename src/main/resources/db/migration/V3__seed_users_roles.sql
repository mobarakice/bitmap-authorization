-- =============================================================================
-- V3: Seed Roles, Users, and Permission Mappings
-- =============================================================================

-- Roles
INSERT INTO roles (id, name, description) VALUES
(1, 'ADMIN', 'Full system administrator with all permissions'),
(2, 'STAFF', 'Regular staff with operational access'),
(3, 'HR',    'Human resources with employee management access');

-- Reset sequence
SELECT setval('roles_id_seq', 3);

-- Users (password is BCrypt hash of "password123")
INSERT INTO users (username, password_hash, role_id) VALUES
('admin',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 1),
('staff1',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 2),
('staff2',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 2),
('hr1',     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 3),
('hr2',     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 3);

-- =============================================================================
-- Role → Permission Mappings
-- =============================================================================

-- ADMIN: all permissions (0-39)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 1, id FROM permissions;

-- STAFF: user read, orders, products, reports read, dashboard (selected)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 2, id FROM permissions WHERE bit_index IN (
    0, 2, 6,                 -- users: list, get, get-perms
    14, 15, 16, 17, 19,      -- orders: list, create, get, update, status
    20, 22, 25,              -- products: list, get, search
    26, 27, 30               -- reports: sales, inventory, dashboard
);

-- HR: user read, HR full, reports (user reports + dashboard)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 3, id FROM permissions WHERE bit_index IN (
    0, 2, 6,                 -- users: list, get, get-perms
    28, 30,                  -- reports: user reports, dashboard
    31, 32, 33, 34, 35, 36   -- hr: full access
);

-- =============================================================================
-- User-level Overrides
-- =============================================================================

-- staff1 gets extra permission: create products (bit_index=21)
INSERT INTO user_allow_permissions (user_id, permission_id)
SELECT u.id, p.id FROM users u, permissions p
WHERE u.username = 'staff1' AND p.bit_index = 21;

-- staff2 has denied permission: cannot create orders (bit_index=15)
INSERT INTO user_deny_permissions (user_id, permission_id)
SELECT u.id, p.id FROM users u, permissions p
WHERE u.username = 'staff2' AND p.bit_index = 15;

-- hr1 gets extra permission: export reports (bit_index=29)
INSERT INTO user_allow_permissions (user_id, permission_id)
SELECT u.id, p.id FROM users u, permissions p
WHERE u.username = 'hr1' AND p.bit_index = 29;

-- hr2 denied: cannot process payroll (bit_index=36)
INSERT INTO user_deny_permissions (user_id, permission_id)
SELECT u.id, p.id FROM users u, permissions p
WHERE u.username = 'hr2' AND p.bit_index = 36;
