-- =============================================================================
-- V4: Airline PSS/CRS — 50,000 Permissions across 13 service domains
-- =============================================================================
-- Services: flights, inventory, pnl, pnr, gds, aircraft-types, aircrafts,
--           prices, sales, seatmaps, ssr, payments, notifications
--
-- Generation strategy:
--   13 services × 20 sub-resources × 11 path patterns × 3 versions × 6 methods
--   = 51,480 combinations, capped at 50,000
-- =============================================================================

-- Clear existing seed data (V2, V3)
TRUNCATE TABLE users, permissions, roles, user_deny_permissions, user_allow_permissions, role_permissions CASCADE;

-- Reset sequences
ALTER SEQUENCE permissions_id_seq RESTART WITH 1;
ALTER SEQUENCE roles_id_seq RESTART WITH 1;
ALTER SEQUENCE users_id_seq RESTART WITH 1;

-- Generate 50,000 permissions from PSS domain model
INSERT INTO permissions (resource, action, bit_index, description)
SELECT
    resource,
    method AS action,
    (ROW_NUMBER() OVER (ORDER BY svc, sub, ver, suffix, method)) - 1 AS bit_index,
    svc || ' — ' || method || ' ' || sub || suffix AS description
FROM (
    -- =========================================================================
    -- Service × Sub-resource definitions (13 services × 20 subs each = 260)
    -- =========================================================================
    SELECT svc, unnest(subs) AS sub
    FROM (VALUES
        ('flights',         ARRAY[
            'schedules','segments','codeshares','connections','status',
            'delays','crew','gates','routes','stopovers',
            'upgrades','ancillaries','catering','fuel','loadsheets',
            'weather','slots','turnarounds','ferry','diversions']),
        ('inventory',       ARRAY[
            'fare-classes','availability','allotments','overbooking','seats',
            'buckets','caps','holds','waitlists','standby',
            'noshows','oversold','block-space','groups','interline',
            'codeshare-avail','revenue','demand','forecasts','optimization']),
        ('pnl',             ARRAY[
            'manifests','boarding','checkin','passengers','counts',
            'deadlines','gate-info','security','transfers','connections',
            'bags','weights','loads','deportees','transit',
            'vip-lists','wheelchair-lists','um-lists','crew-lists','jumpseaters']),
        ('pnr',             ARRAY[
            'bookings','itineraries','contacts','remarks','history',
            'tickets','segments','travelers','documents','frequent-flyer',
            'preferences','groups','endorsements','queues','time-limits',
            'splits','merges','reissues','exchanges','refund-requests']),
        ('gds',             ARRAY[
            'channels','feeds','availability','fares','rules',
            'segments','content','direct-connect','ndc','offers',
            'orders','reshop','ticketing','voids','exchanges',
            'refunds','queues','pnr-sync','fare-quotes','mini-rules']),
        ('aircraft-types',  ARRAY[
            'configurations','certifications','specs','variants','cabin-layouts',
            'cargo-holds','range-data','performance','compliance','maintenance-plans',
            'seating-capacity','fuel-capacity','weight-limits','dimensions','engines',
            'avionics','modifications','supplements','etops','designators']),
        ('aircrafts',       ARRAY[
            'fleet','registrations','assignments','maintenance','inspections',
            'components','engines','documents','utilization','leases',
            'insurance','modifications','paint-schemes','wifi-config','ife-config',
            'galley-config','lavatory-config','cargo-config','fuel-systems','apu']),
        ('prices',          ARRAY[
            'fares','rules','taxes','surcharges','promotions',
            'bundles','discounts','penalties','waivers','ancillary-prices',
            'dynamic-pricing','currencies','exchange-rates','fare-basis','fare-families',
            'branded-fares','corporate-fares','tour-fares','group-fares','loyalty-fares']),
        ('sales',           ARRAY[
            'agents','agencies','commissions','quotas','targets',
            'reports','incentives','contracts','overrides','bsp',
            'arc','iata','corporate-accounts','tour-operators','consolidators',
            'online-travel','direct-sales','partnerships','affiliates','revenue-share']),
        ('seatmaps',        ARRAY[
            'cabins','layouts','assignments','blocked-seats','premium-seats',
            'emergency-rows','bulkhead','window-seats','aisle-seats','middle-seats',
            'extra-legroom','recline-seats','bassinets','crew-rest','galleys',
            'lavatories','exits','zones','upgrades','preferences']),
        ('ssr',             ARRAY[
            'meals','wheelchair','medical','pets','unaccompanied-minors',
            'oxygen','stretcher','blind-assistance','deaf-assistance','mobility',
            'priority-boarding','lounge-access','vip-service','baggage-special','sports-equipment',
            'musical-instruments','dangerous-goods','live-animals','diplomatic','excess-baggage']),
        ('payments',        ARRAY[
            'transactions','refunds','vouchers','methods','settlements',
            'reconciliation','disputes','chargebacks','installments','wallets',
            'loyalty-points','invoices','receipts','credit-notes','debit-notes',
            'forex','collections','escrow','guarantees','payment-plans']),
        ('notifications',   ARRAY[
            'emails','sms','push','templates','schedules',
            'delivery-status','preferences','channels','triggers','escalations',
            'alerts','reports','campaigns','subscriptions','opt-outs',
            'bounces','complaints','analytics','ab-tests','personalization'])
    ) AS t(svc, subs)
) AS service_subs
-- =========================================================================
-- Cross join with API versions, HTTP methods, and path suffixes
-- =========================================================================
CROSS JOIN (VALUES ('v1'), ('v2'), ('v3')) AS versions(ver)
CROSS JOIN (VALUES ('GET'), ('POST'), ('PUT'), ('PATCH'), ('DELETE'), ('OPTIONS')) AS methods(method)
CROSS JOIN (VALUES
    ('',               'list'),
    ('/{id}',          'by-id'),
    ('/{id}/details',  'details'),
    ('/search',        'search'),
    ('/export',        'export'),
    ('/batch',         'batch'),
    ('/stats',         'stats'),
    ('/{id}/history',  'history'),
    ('/{id}/archive',  'archive'),
    ('/{id}/approve',  'approve'),
    ('/{id}/validate', 'validate')
) AS suffixes(suffix, suffix_desc),
-- Build the final resource path
LATERAL (
    SELECT '/api/' || ver || '/' || svc || '/' || sub || suffix AS resource
) AS path
LIMIT 50000;
