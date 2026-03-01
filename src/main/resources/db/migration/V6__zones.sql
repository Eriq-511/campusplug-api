-- Phase G7: Campus zone boundaries + user_zones tracking + zone_tag on listings

-- ============================================================
-- zones — polygon boundaries for campus zones
-- ============================================================
CREATE TABLE IF NOT EXISTS zones (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    tag         TEXT NOT NULL UNIQUE,
    access_type TEXT NOT NULL DEFAULT 'full'
                CHECK (access_type IN ('full', 'buffer'))
);

SELECT AddGeometryColumn('zones', 'boundary', 4326, 'POLYGON', 2);
CREATE INDEX IF NOT EXISTS idx_zones_boundary ON zones USING GIST (boundary);

-- ============================================================
-- user_zones — which zone each user was last detected in
-- ============================================================
CREATE TABLE IF NOT EXISTS user_zones (
    user_id    BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    zone_tag   TEXT NOT NULL REFERENCES zones(tag) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- listings — add zone_tag column for zone-filtered queries
-- ============================================================
ALTER TABLE listings ADD COLUMN IF NOT EXISTS zone_tag TEXT REFERENCES zones(tag) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_listings_zone_tag ON listings (zone_tag);

-- ============================================================
-- Seed: 10 real Kihumuro campus zones — KMZ-extracted coordinates
-- Source: KIhumuro_Zones.kmz (coordinates in PostGIS lng lat order)
-- ============================================================

-- 1. Kihumuro zone (Main Campus)
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Kihumuro zone', 'kihumuro_main', 'full',
  ST_GeomFromText('POLYGON((
    30.5933968 -0.5919473,
    30.5905858 -0.5966678,
    30.6011215 -0.5974402,
    30.6012073 -0.5943934,
    30.5933968 -0.5919473
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;

-- 2. Mile 4 zone
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 4 zone', 'mile_4', 'full',
  ST_GeomFromText('POLYGON((
    30.6093997 -0.5983348,
    30.6115830 -0.5994559,
    30.6120229 -0.5985333,
    30.6101453 -0.5977501,
    30.6093997 -0.5983348
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;

-- 3. Path hostel zone
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Path hostel zone', 'path_hostel', 'full',
  ST_GeomFromText('POLYGON((
    30.6105812 -0.5944472,
    30.6073357 -0.5941092,
    30.6073250 -0.5944579,
    30.6104149 -0.5949997,
    30.6105812 -0.5944472
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;

-- 4. Mama Belinda zone
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mama Belinda zone', 'mama_belinda', 'full',
  ST_GeomFromText('POLYGON((
    30.6108645 -0.5935245,
    30.6132677 -0.5940395,
    30.6133643 -0.5932778,
    30.6110790 -0.5922586,
    30.6108645 -0.5935245
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;

-- 5. Mile 5 zone
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 5 zone', 'mile_5', 'full',
  ST_GeomFromText('POLYGON((
    30.6109449 -0.5831127,
    30.6102958 -0.5834990,
    30.6107143 -0.5853281,
    30.6114921 -0.5850921,
    30.6109449 -0.5831127
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;

-- 6. Mirrors zone
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mirrors zone', 'mirrors', 'full',
  ST_GeomFromText('POLYGON((
    30.6170087 -0.6017127,
    30.6182828 -0.6025710,
    30.6180870 -0.6013292,
    30.6170087 -0.6017127
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;

-- 7. Mile 3 Zone A
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 3 Zone A', 'mile_3_a', 'full',
  ST_GeomFromText('POLYGON((
    30.6290089 -0.6073286,
    30.6270133 -0.6056657,
    30.6253396 -0.6043890,
    30.6240951 -0.6041101,
    30.6233441 -0.6054189,
    30.6256830 -0.6072106,
    30.6279789 -0.6082727,
    30.6290089 -0.6073286
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;

-- 8. Mile 3 zone B
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 3 zone B', 'mile_3_b', 'full',
  ST_GeomFromText('POLYGON((
    30.6219815 -0.6047216,
    30.6189238 -0.6067814,
    30.6195246 -0.6089271,
    30.6230115 -0.6114697,
    30.6276034 -0.6081868,
    30.6255864 -0.6072535,
    30.6219815 -0.6047216
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;

-- 9. Kiyanja zone
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Kiyanja zone', 'kiyanja', 'full',
  ST_GeomFromText('POLYGON((
    30.6372884 -0.6119066,
    30.6380394 -0.6105334,
    30.6388655 -0.6089349,
    30.6390801 -0.6086023,
    30.6365266 -0.6081517,
    30.6345740 -0.6083770,
    30.6341770 -0.6108231,
    30.6372884 -0.6119066
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;

-- 10. Ruharo zone
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Ruharo zone', 'ruharo', 'full',
  ST_GeomFromText('POLYGON((
    30.6290447 -0.6087281,
    30.6316732 -0.6098653,
    30.6333791 -0.6079878,
    30.6300532 -0.6065288,
    30.6290447 -0.6087281
  ))', 4326)
) ON CONFLICT (tag) DO UPDATE
    SET name = EXCLUDED.name,
        boundary = EXCLUDED.boundary;
