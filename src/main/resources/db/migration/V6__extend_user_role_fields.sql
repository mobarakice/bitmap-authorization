-- ============================================================================
-- V6: Extend User and Role entities to match architecture spec
-- ============================================================================

-- roles: add is_active and is_system flags
ALTER TABLE roles ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE roles ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT false;

-- users: add profile fields
ALTER TABLE users ADD COLUMN first_name VARCHAR(100);
ALTER TABLE users ADD COLUMN last_name VARCHAR(100);
ALTER TABLE users ADD COLUMN email VARCHAR(255);
ALTER TABLE users ADD COLUMN mobile_no VARCHAR(20);
ALTER TABLE users ADD COLUMN signcode VARCHAR(50);
ALTER TABLE users ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT false;

-- make role_id nullable (users can exist without a role)
ALTER TABLE users ALTER COLUMN role_id DROP NOT NULL;

-- unique constraints on new fields
ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email);
ALTER TABLE users ADD CONSTRAINT uk_users_mobile_no UNIQUE (mobile_no);
ALTER TABLE users ADD CONSTRAINT uk_users_signcode UNIQUE (signcode);

-- seed: mark ADMIN role as system role
UPDATE roles SET is_system = true WHERE name = 'ADMIN';
