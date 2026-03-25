-- =============================================================================
-- V5: Airline PSS/CRS — 100 Roles, 200 Users, Role-Permission Mappings
-- =============================================================================
-- Role categories:
--   1-5    : Executive      (all 50k permissions)
--   6-18   : Department Head (full access to their service domains)
--   19-38  : Manager        (read/write on their domains)
--   39-58  : Senior Staff   (operational access)
--   59-83  : Staff          (basic operational)
--   84-95  : Junior/Trainee (read-only)
--   96-100 : System/API     (service-account scoped)
--
-- Permission bit_index ranges per service (~3,846 each, ordered alphabetically):
--   aircraft-types : 0      - 3,959
--   aircrafts      : 3,960  - 7,919
--   flights        : 7,920  - 11,879
--   gds            : 11,880 - 15,839
--   inventory      : 15,840 - 19,799
--   notifications  : 19,800 - 23,759
--   payments       : 23,760 - 27,719
--   pnl            : 27,720 - 31,679
--   pnr            : 31,680 - 35,639
--   prices         : 35,640 - 39,599
--   sales          : 39,600 - 43,559
--   seatmaps       : 43,560 - 47,519
--   ssr            : 47,520 - 49,999
-- =============================================================================

-- =========================================================================
-- 1. INSERT 100 ROLES
-- =========================================================================

-- Executive roles (1-5)
INSERT INTO roles (id, name, description) VALUES
(1,  'CEO',                        'Chief Executive Officer — full system access'),
(2,  'VP_OPERATIONS',              'VP Operations — all operational services'),
(3,  'VP_COMMERCIAL',              'VP Commercial — revenue, sales, pricing'),
(4,  'VP_FINANCE',                 'VP Finance — payments, billing, reporting'),
(5,  'CTO',                        'Chief Technology Officer — full system access');

-- Department Head roles (6-18)
INSERT INTO roles (id, name, description) VALUES
(6,  'HEAD_FLIGHT_OPS',            'Head of Flight Operations'),
(7,  'HEAD_REVENUE_MGMT',          'Head of Revenue Management'),
(8,  'HEAD_RESERVATIONS',          'Head of Reservations & PNR'),
(9,  'HEAD_GROUND_OPS',            'Head of Ground Operations & PNL'),
(10, 'HEAD_DISTRIBUTION',          'Head of GDS & Distribution'),
(11, 'HEAD_FLEET_MGMT',            'Head of Fleet & Aircraft Management'),
(12, 'HEAD_PRICING',               'Head of Pricing & Fare Management'),
(13, 'HEAD_SALES',                 'Head of Sales & Commercial Partners'),
(14, 'HEAD_CABIN_PLANNING',        'Head of Cabin Planning & Seatmaps'),
(15, 'HEAD_CUSTOMER_SERVICE',      'Head of Guest Services & SSR'),
(16, 'HEAD_PAYMENTS',              'Head of Payments & Financial Ops'),
(17, 'HEAD_COMMS',                 'Head of Communications & Notifications'),
(18, 'HEAD_INVENTORY',             'Head of Inventory Control');

-- Manager roles (19-38)
INSERT INTO roles (id, name, description) VALUES
(19, 'MGR_FLIGHT_SCHEDULE',        'Flight Schedule Manager'),
(20, 'MGR_FLIGHT_DISPATCH',        'Flight Dispatch Manager'),
(21, 'MGR_INVENTORY_CONTROL',      'Inventory Control Manager'),
(22, 'MGR_FARE_CLASS',             'Fare Class Manager'),
(23, 'MGR_RESERVATIONS',           'Reservations Manager'),
(24, 'MGR_TICKETING',              'Ticketing Manager'),
(25, 'MGR_AIRPORT_OPS',            'Airport Operations Manager'),
(26, 'MGR_BOARDING',               'Boarding & Check-in Manager'),
(27, 'MGR_GDS_CONTENT',            'GDS Content Manager'),
(28, 'MGR_NDC',                    'NDC & Direct Connect Manager'),
(29, 'MGR_FLEET',                  'Fleet Manager'),
(30, 'MGR_MAINTENANCE',            'MRO Maintenance Manager'),
(31, 'MGR_PRICING_DOMESTIC',       'Domestic Pricing Manager'),
(32, 'MGR_PRICING_INTL',           'International Pricing Manager'),
(33, 'MGR_SALES_CORPORATE',        'Corporate Sales Manager'),
(34, 'MGR_SALES_LEISURE',          'Leisure Sales Manager'),
(35, 'MGR_CABIN_CONFIG',           'Cabin Configuration Manager'),
(36, 'MGR_GUEST_SERVICES',         'Guest Services Manager'),
(37, 'MGR_PAYMENT_OPS',            'Payment Operations Manager'),
(38, 'MGR_COMMS_OPS',              'Communications Operations Manager');

-- Senior Staff roles (39-58)
INSERT INTO roles (id, name, description) VALUES
(39, 'SR_FLIGHT_PLANNER',          'Senior Flight Planner'),
(40, 'SR_ROUTE_ANALYST',           'Senior Route Analyst'),
(41, 'SR_INVENTORY_ANALYST',       'Senior Inventory Analyst'),
(42, 'SR_REVENUE_ANALYST',         'Senior Revenue Analyst'),
(43, 'SR_RESERVATION_AGENT',       'Senior Reservation Agent'),
(44, 'SR_TICKETING_AGENT',         'Senior Ticketing Agent'),
(45, 'SR_AIRPORT_AGENT',           'Senior Airport Agent'),
(46, 'SR_CHECKIN_AGENT',           'Senior Check-in Agent'),
(47, 'SR_GDS_ANALYST',             'Senior GDS Analyst'),
(48, 'SR_DISTRIBUTION_ANALYST',    'Senior Distribution Analyst'),
(49, 'SR_FLEET_ENGINEER',          'Senior Fleet Engineer'),
(50, 'SR_MAINTENANCE_ENGINEER',    'Senior Maintenance Engineer'),
(51, 'SR_PRICING_ANALYST',         'Senior Pricing Analyst'),
(52, 'SR_FARE_ANALYST',            'Senior Fare Analyst'),
(53, 'SR_SALES_EXECUTIVE',         'Senior Sales Executive'),
(54, 'SR_ACCOUNT_MANAGER',         'Senior Account Manager'),
(55, 'SR_CABIN_PLANNER',           'Senior Cabin Planner'),
(56, 'SR_GUEST_SERVICES_AGENT',    'Senior Guest Services Agent'),
(57, 'SR_PAYMENT_ANALYST',         'Senior Payment Analyst'),
(58, 'SR_NOTIFICATION_ADMIN',      'Senior Notification Administrator');

-- Staff roles (59-83)
INSERT INTO roles (id, name, description) VALUES
(59, 'FLIGHT_COORDINATOR',         'Flight Coordinator'),
(60, 'ROUTE_PLANNER',              'Route Planner'),
(61, 'INVENTORY_AGENT',            'Inventory Agent'),
(62, 'AVAILABILITY_AGENT',         'Availability Agent'),
(63, 'RESERVATION_AGENT',          'Reservation Agent'),
(64, 'BOOKING_AGENT',              'Booking Agent'),
(65, 'AIRPORT_AGENT',              'Airport Agent'),
(66, 'CHECKIN_AGENT',              'Check-in Agent'),
(67, 'GDS_OPERATOR',               'GDS Operator'),
(68, 'NDC_OPERATOR',               'NDC Operator'),
(69, 'FLEET_TECHNICIAN',           'Fleet Technician'),
(70, 'MAINTENANCE_TECHNICIAN',     'Maintenance Technician'),
(71, 'PRICING_COORDINATOR',        'Pricing Coordinator'),
(72, 'FARE_ANALYST_JUNIOR',        'Junior Fare Analyst'),
(73, 'SALES_AGENT',                'Sales Agent'),
(74, 'SALES_SUPPORT',              'Sales Support'),
(75, 'SEATMAP_AGENT',              'Seatmap Agent'),
(76, 'CABIN_CREW_COORDINATOR',     'Cabin Crew Coordinator'),
(77, 'GUEST_SERVICES_AGENT',       'Guest Services Agent'),
(78, 'SSR_HANDLER',                'SSR Request Handler'),
(79, 'PAYMENT_PROCESSOR',          'Payment Processor'),
(80, 'REFUND_AGENT',               'Refund Agent'),
(81, 'NOTIFICATION_OPERATOR',      'Notification Operator'),
(82, 'CAMPAIGN_OPERATOR',          'Campaign Operator'),
(83, 'OPERATIONS_SUPPORT',         'General Operations Support');

-- Junior/Trainee roles (84-95)
INSERT INTO roles (id, name, description) VALUES
(84, 'TRAINEE_RESERVATIONS',       'Trainee — Reservations (read-only)'),
(85, 'TRAINEE_TICKETING',          'Trainee — Ticketing (read-only)'),
(86, 'TRAINEE_AIRPORT',            'Trainee — Airport Ops (read-only)'),
(87, 'TRAINEE_INVENTORY',          'Trainee — Inventory (read-only)'),
(88, 'TRAINEE_PRICING',            'Trainee — Pricing (read-only)'),
(89, 'TRAINEE_SALES',              'Trainee — Sales (read-only)'),
(90, 'TRAINEE_FLEET',              'Trainee — Fleet (read-only)'),
(91, 'TRAINEE_GUEST_SERVICES',     'Trainee — Guest Services (read-only)'),
(92, 'INTERN_OPERATIONS',          'Intern — Operations (read-only)'),
(93, 'INTERN_COMMERCIAL',          'Intern — Commercial (read-only)'),
(94, 'INTERN_TECHNOLOGY',          'Intern — Technology (read-only)'),
(95, 'AUDITOR_READONLY',           'External Auditor (read-only all)');

-- System/API roles (96-100)
INSERT INTO roles (id, name, description) VALUES
(96, 'SVC_BOOKING_ENGINE',         'Service Account — Booking Engine API'),
(97, 'SVC_PAYMENT_GATEWAY',        'Service Account — Payment Gateway'),
(98, 'SVC_GDS_CONNECTOR',          'Service Account — GDS Connector'),
(99, 'SVC_NOTIFICATION_ENGINE',    'Service Account — Notification Engine'),
(100,'SVC_REPORTING_ETL',          'Service Account — Reporting & ETL');

SELECT setval('roles_id_seq', 100);

-- =========================================================================
-- 2. INSERT 200 USERS (password hash = BCrypt of "password123")
-- =========================================================================
INSERT INTO users (username, password_hash, role_id, enabled)
SELECT
    'user' || LPAD(n::TEXT, 3, '0') AS username,
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy' AS password_hash,
    CASE
        -- 5 executives (roles 1-5)
        WHEN n <= 5  THEN n
        -- 13 department heads (roles 6-18)
        WHEN n <= 18 THEN n
        -- 20 managers (roles 19-38) — 1 user each
        WHEN n <= 38 THEN n
        -- 20 senior staff (roles 39-58) — 1 user each
        WHEN n <= 58 THEN n
        -- Remaining 142 users distributed across staff/junior/system roles
        WHEN n <= 100 THEN 59 + ((n - 59) % 25)  -- staff roles 59-83
        WHEN n <= 170 THEN 59 + ((n - 101) % 25)  -- more staff
        WHEN n <= 190 THEN 84 + ((n - 171) % 12)  -- junior/trainee 84-95
        ELSE               96 + ((n - 191) % 5)   -- system roles 96-100
    END AS role_id,
    TRUE
FROM generate_series(1, 200) AS n;

-- =========================================================================
-- 3. ROLE-PERMISSION MAPPINGS
-- =========================================================================
-- Permission bit_indexes are ordered alphabetically by service name.
-- Each service occupies ~3,846 consecutive bit_indexes (50000 / 13).
-- We assign permissions by bit_index ranges for efficiency.
-- =========================================================================

-- ---------------------------------------------------------------------------
-- EXECUTIVE (roles 1-5): ALL 50,000 permissions
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.id BETWEEN 1 AND 5;

-- ---------------------------------------------------------------------------
-- DEPARTMENT HEADS (roles 6-18): Full access to their service domain(s)
-- Each service is ~3,846 bit_indexes wide. We assign by alphabetical order:
--   0-3959      = aircraft-types    → role 11 (HEAD_FLEET_MGMT)
--   3960-7919   = aircrafts         → role 11 (HEAD_FLEET_MGMT)
--   7920-11879  = flights           → role 6  (HEAD_FLIGHT_OPS)
--   11880-15839 = gds               → role 10 (HEAD_DISTRIBUTION)
--   15840-19799 = inventory         → role 18 (HEAD_INVENTORY)
--   19800-23759 = notifications     → role 17 (HEAD_COMMS)
--   23760-27719 = payments          → role 16 (HEAD_PAYMENTS)
--   27720-31679 = pnl               → role 9  (HEAD_GROUND_OPS)
--   31680-35639 = pnr               → role 8  (HEAD_RESERVATIONS)
--   35640-39599 = prices            → role 12 (HEAD_PRICING)
--   39600-43559 = sales             → role 13 (HEAD_SALES)
--   43560-47519 = seatmaps          → role 14 (HEAD_CABIN_PLANNING)
--   47520-49999 = ssr               → role 15 (HEAD_CUSTOMER_SERVICE)
-- ---------------------------------------------------------------------------

-- HEAD_FLIGHT_OPS (6) → flights
INSERT INTO role_permissions (role_id, permission_id)
SELECT 6, p.id FROM permissions p WHERE p.bit_index BETWEEN 7920 AND 11879;

-- HEAD_REVENUE_MGMT (7) → inventory + prices
INSERT INTO role_permissions (role_id, permission_id)
SELECT 7, p.id FROM permissions p WHERE p.bit_index BETWEEN 15840 AND 19799
UNION ALL
SELECT 7, p.id FROM permissions p WHERE p.bit_index BETWEEN 35640 AND 39599;

-- HEAD_RESERVATIONS (8) → pnr
INSERT INTO role_permissions (role_id, permission_id)
SELECT 8, p.id FROM permissions p WHERE p.bit_index BETWEEN 31680 AND 35639;

-- HEAD_GROUND_OPS (9) → pnl
INSERT INTO role_permissions (role_id, permission_id)
SELECT 9, p.id FROM permissions p WHERE p.bit_index BETWEEN 27720 AND 31679;

-- HEAD_DISTRIBUTION (10) → gds
INSERT INTO role_permissions (role_id, permission_id)
SELECT 10, p.id FROM permissions p WHERE p.bit_index BETWEEN 11880 AND 15839;

-- HEAD_FLEET_MGMT (11) → aircraft-types + aircrafts
INSERT INTO role_permissions (role_id, permission_id)
SELECT 11, p.id FROM permissions p WHERE p.bit_index BETWEEN 0 AND 7919;

-- HEAD_PRICING (12) → prices
INSERT INTO role_permissions (role_id, permission_id)
SELECT 12, p.id FROM permissions p WHERE p.bit_index BETWEEN 35640 AND 39599;

-- HEAD_SALES (13) → sales
INSERT INTO role_permissions (role_id, permission_id)
SELECT 13, p.id FROM permissions p WHERE p.bit_index BETWEEN 39600 AND 43559;

-- HEAD_CABIN_PLANNING (14) → seatmaps
INSERT INTO role_permissions (role_id, permission_id)
SELECT 14, p.id FROM permissions p WHERE p.bit_index BETWEEN 43560 AND 47519;

-- HEAD_CUSTOMER_SERVICE (15) → ssr
INSERT INTO role_permissions (role_id, permission_id)
SELECT 15, p.id FROM permissions p WHERE p.bit_index BETWEEN 47520 AND 49999;

-- HEAD_PAYMENTS (16) → payments
INSERT INTO role_permissions (role_id, permission_id)
SELECT 16, p.id FROM permissions p WHERE p.bit_index BETWEEN 23760 AND 27719;

-- HEAD_COMMS (17) → notifications
INSERT INTO role_permissions (role_id, permission_id)
SELECT 17, p.id FROM permissions p WHERE p.bit_index BETWEEN 19800 AND 23759;

-- HEAD_INVENTORY (18) → inventory
INSERT INTO role_permissions (role_id, permission_id)
SELECT 18, p.id FROM permissions p WHERE p.bit_index BETWEEN 15840 AND 19799;

-- ---------------------------------------------------------------------------
-- MANAGERS (roles 19-38): Read + Write on their domain (GET, POST, PUT only)
-- Roughly half the service's permissions (non-destructive actions)
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.id
FROM (VALUES
    -- Flight domain managers
    (19, 7920,  11879),  -- MGR_FLIGHT_SCHEDULE → flights
    (20, 7920,  11879),  -- MGR_FLIGHT_DISPATCH → flights
    -- Inventory domain managers
    (21, 15840, 19799),  -- MGR_INVENTORY_CONTROL → inventory
    (22, 15840, 19799),  -- MGR_FARE_CLASS → inventory
    -- PNR/Reservations managers
    (23, 31680, 35639),  -- MGR_RESERVATIONS → pnr
    (24, 31680, 35639),  -- MGR_TICKETING → pnr
    -- PNL/Airport managers
    (25, 27720, 31679),  -- MGR_AIRPORT_OPS → pnl
    (26, 27720, 31679),  -- MGR_BOARDING → pnl
    -- GDS managers
    (27, 11880, 15839),  -- MGR_GDS_CONTENT → gds
    (28, 11880, 15839),  -- MGR_NDC → gds
    -- Fleet managers
    (29, 0,     7919),   -- MGR_FLEET → aircraft-types + aircrafts
    (30, 0,     7919),   -- MGR_MAINTENANCE → aircraft-types + aircrafts
    -- Pricing managers
    (31, 35640, 39599),  -- MGR_PRICING_DOMESTIC → prices
    (32, 35640, 39599),  -- MGR_PRICING_INTL → prices
    -- Sales managers
    (33, 39600, 43559),  -- MGR_SALES_CORPORATE → sales
    (34, 39600, 43559),  -- MGR_SALES_LEISURE → sales
    -- Seatmap manager
    (35, 43560, 47519),  -- MGR_CABIN_CONFIG → seatmaps
    -- SSR manager
    (36, 47520, 49999),  -- MGR_GUEST_SERVICES → ssr
    -- Payment manager
    (37, 23760, 27719),  -- MGR_PAYMENT_OPS → payments
    -- Comms manager
    (38, 19800, 23759)   -- MGR_COMMS_OPS → notifications
) AS r(role_id, bit_lo, bit_hi)
JOIN permissions p ON p.bit_index BETWEEN r.bit_lo AND r.bit_hi
WHERE p.action IN ('GET', 'POST', 'PUT', 'PATCH');

-- ---------------------------------------------------------------------------
-- SENIOR STAFF (roles 39-58): Read + limited write on their domain
-- GET + POST only
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.id
FROM (VALUES
    (39, 7920,  11879),  (40, 7920,  11879),   -- flights
    (41, 15840, 19799),  (42, 15840, 19799),   -- inventory
    (43, 31680, 35639),  (44, 31680, 35639),   -- pnr
    (45, 27720, 31679),  (46, 27720, 31679),   -- pnl
    (47, 11880, 15839),  (48, 11880, 15839),   -- gds
    (49, 0,     7919),   (50, 0,     7919),    -- aircraft-types + aircrafts
    (51, 35640, 39599),  (52, 35640, 39599),   -- prices
    (53, 39600, 43559),  (54, 39600, 43559),   -- sales
    (55, 43560, 47519),  (56, 47520, 49999),   -- seatmaps, ssr
    (57, 23760, 27719),  (58, 19800, 23759)    -- payments, notifications
) AS r(role_id, bit_lo, bit_hi)
JOIN permissions p ON p.bit_index BETWEEN r.bit_lo AND r.bit_hi
WHERE p.action IN ('GET', 'POST');

-- ---------------------------------------------------------------------------
-- STAFF (roles 59-83): Read-only on their service domain (GET only)
-- Plus limited POST on core operational paths
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.id
FROM (VALUES
    (59, 7920,  11879),  (60, 7920,  11879),   -- flights
    (61, 15840, 19799),  (62, 15840, 19799),   -- inventory
    (63, 31680, 35639),  (64, 31680, 35639),   -- pnr
    (65, 27720, 31679),  (66, 27720, 31679),   -- pnl
    (67, 11880, 15839),  (68, 11880, 15839),   -- gds
    (69, 0,     7919),   (70, 0,     7919),    -- aircraft-types + aircrafts
    (71, 35640, 39599),  (72, 35640, 39599),   -- prices
    (73, 39600, 43559),  (74, 39600, 43559),   -- sales
    (75, 43560, 47519),  (76, 43560, 47519),   -- seatmaps
    (77, 47520, 49999),  (78, 47520, 49999),   -- ssr
    (79, 23760, 27719),  (80, 23760, 27719),   -- payments
    (81, 19800, 23759),  (82, 19800, 23759),   -- notifications
    (83, 0,     49999)                          -- ops support: read-all
) AS r(role_id, bit_lo, bit_hi)
JOIN permissions p ON p.bit_index BETWEEN r.bit_lo AND r.bit_hi
WHERE p.action = 'GET';

-- ---------------------------------------------------------------------------
-- JUNIOR/TRAINEE (roles 84-95): Read-only on limited scope
-- Only GET on the first 1000 bit_indexes of their domain
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.id
FROM (VALUES
    (84, 31680, 32679),  -- trainee reservations (pnr subset)
    (85, 31680, 32679),  -- trainee ticketing (pnr subset)
    (86, 27720, 28719),  -- trainee airport (pnl subset)
    (87, 15840, 16839),  -- trainee inventory
    (88, 35640, 36639),  -- trainee pricing
    (89, 39600, 40599),  -- trainee sales
    (90, 0,     999),    -- trainee fleet (aircraft-types subset)
    (91, 47520, 48519),  -- trainee guest services (ssr subset)
    (92, 7920,  9919),   -- intern operations (flights subset)
    (93, 35640, 37639),  -- intern commercial (prices subset)
    (94, 0,     1999),   -- intern technology (aircraft-types subset)
    (95, 0,     49999)   -- auditor: read-only ALL
) AS r(role_id, bit_lo, bit_hi)
JOIN permissions p ON p.bit_index BETWEEN r.bit_lo AND r.bit_hi
WHERE p.action = 'GET';

-- ---------------------------------------------------------------------------
-- SYSTEM/API (roles 96-100): Targeted service account permissions
-- ---------------------------------------------------------------------------
-- SVC_BOOKING_ENGINE: pnr + inventory (GET, POST, PUT)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 96, p.id FROM permissions p
WHERE p.bit_index BETWEEN 31680 AND 35639 AND p.action IN ('GET', 'POST', 'PUT')
UNION ALL
SELECT 96, p.id FROM permissions p
WHERE p.bit_index BETWEEN 15840 AND 19799 AND p.action IN ('GET', 'POST');

-- SVC_PAYMENT_GATEWAY: payments (GET, POST, PUT, PATCH)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 97, p.id FROM permissions p
WHERE p.bit_index BETWEEN 23760 AND 27719 AND p.action IN ('GET', 'POST', 'PUT', 'PATCH');

-- SVC_GDS_CONNECTOR: gds + inventory (GET, POST)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 98, p.id FROM permissions p
WHERE p.bit_index BETWEEN 11880 AND 15839 AND p.action IN ('GET', 'POST')
UNION ALL
SELECT 98, p.id FROM permissions p
WHERE p.bit_index BETWEEN 15840 AND 19799 AND p.action = 'GET';

-- SVC_NOTIFICATION_ENGINE: notifications (all methods)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 99, p.id FROM permissions p
WHERE p.bit_index BETWEEN 19800 AND 23759;

-- SVC_REPORTING_ETL: read-only across all services
INSERT INTO role_permissions (role_id, permission_id)
SELECT 100, p.id FROM permissions p WHERE p.action = 'GET';

-- =========================================================================
-- 4. USER-LEVEL OVERRIDES (sample allow/deny)
-- =========================================================================

-- user019 (MGR_FLIGHT_SCHEDULE) gets extra inventory read access
INSERT INTO user_allow_permissions (user_id, permission_id)
SELECT u.id, p.id FROM users u, permissions p
WHERE u.username = 'user019'
  AND p.bit_index BETWEEN 15840 AND 16839
  AND p.action = 'GET';

-- user023 (MGR_RESERVATIONS) denied payment access bit_index range
INSERT INTO user_deny_permissions (user_id, permission_id)
SELECT u.id, p.id FROM users u, permissions p
WHERE u.username = 'user023'
  AND p.bit_index BETWEEN 23760 AND 24759
  AND p.action IN ('POST', 'PUT');

-- user063 (RESERVATION_AGENT) gets extra SSR read access
INSERT INTO user_allow_permissions (user_id, permission_id)
SELECT u.id, p.id FROM users u, permissions p
WHERE u.username = 'user063'
  AND p.bit_index BETWEEN 47520 AND 48519
  AND p.action = 'GET';

-- user079 (PAYMENT_PROCESSOR) denied refund capabilities
INSERT INTO user_deny_permissions (user_id, permission_id)
SELECT u.id, p.id FROM users u, permissions p
WHERE u.username = 'user079'
  AND p.bit_index BETWEEN 23760 AND 24759
  AND p.action = 'DELETE';
