-- =============================================================================
-- V2: Seed Permissions — Sample API permissions with bit_index assignments
-- =============================================================================

-- User Management APIs (bit_index 0-7)
INSERT INTO permissions (resource, action, bit_index, description) VALUES
('/api/users',          'GET',      0,  'List all users'),
('/api/users',          'POST',     1,  'Create a new user'),
('/api/users/{id}',     'GET',      2,  'Get user by ID'),
('/api/users/{id}',     'PUT',      3,  'Update user'),
('/api/users/{id}',     'DELETE',   4,  'Delete user'),
('/api/users/{id}/roles', 'PUT',    5,  'Assign role to user'),
('/api/users/{id}/permissions', 'GET', 6, 'Get user effective permissions'),
('/api/users/search',   'POST',     7,  'Search users');

-- Role Management APIs (bit_index 8-13)
INSERT INTO permissions (resource, action, bit_index, description) VALUES
('/api/roles',          'GET',      8,  'List all roles'),
('/api/roles',          'POST',     9,  'Create a new role'),
('/api/roles/{id}',     'GET',      10, 'Get role by ID'),
('/api/roles/{id}',     'PUT',      11, 'Update role'),
('/api/roles/{id}',     'DELETE',   12, 'Delete role'),
('/api/roles/{id}/permissions', 'GET', 13, 'Get role permissions');

-- Order Management APIs (bit_index 14-19)
INSERT INTO permissions (resource, action, bit_index, description) VALUES
('/api/orders',         'GET',      14, 'List orders'),
('/api/orders',         'POST',     15, 'Create order'),
('/api/orders/{id}',    'GET',      16, 'Get order by ID'),
('/api/orders/{id}',    'PUT',      17, 'Update order'),
('/api/orders/{id}',    'DELETE',   18, 'Cancel order'),
('/api/orders/{id}/status', 'PUT',  19, 'Update order status');

-- Product Management APIs (bit_index 20-25)
INSERT INTO permissions (resource, action, bit_index, description) VALUES
('/api/products',       'GET',      20, 'List products'),
('/api/products',       'POST',     21, 'Create product'),
('/api/products/{id}',  'GET',      22, 'Get product by ID'),
('/api/products/{id}',  'PUT',      23, 'Update product'),
('/api/products/{id}',  'DELETE',   24, 'Delete product'),
('/api/products/search', 'POST',    25, 'Search products');

-- Report APIs (bit_index 26-30)
INSERT INTO permissions (resource, action, bit_index, description) VALUES
('/api/reports/sales',      'GET',  26, 'View sales reports'),
('/api/reports/inventory',  'GET',  27, 'View inventory reports'),
('/api/reports/users',      'GET',  28, 'View user reports'),
('/api/reports/export',     'POST', 29, 'Export reports'),
('/api/reports/dashboard',  'GET',  30, 'View dashboard');

-- HR APIs (bit_index 31-36)
INSERT INTO permissions (resource, action, bit_index, description) VALUES
('/api/hr/employees',       'GET',  31, 'List employees'),
('/api/hr/employees',       'POST', 32, 'Add employee'),
('/api/hr/employees/{id}',  'GET',  33, 'Get employee details'),
('/api/hr/employees/{id}',  'PUT',  34, 'Update employee'),
('/api/hr/payroll',         'GET',  35, 'View payroll'),
('/api/hr/payroll',         'POST', 36, 'Process payroll');

-- Settings APIs (bit_index 37-39)
INSERT INTO permissions (resource, action, bit_index, description) VALUES
('/api/settings',       'GET',      37, 'View settings'),
('/api/settings',       'PUT',      38, 'Update settings'),
('/api/settings/audit', 'GET',      39, 'View audit logs');
