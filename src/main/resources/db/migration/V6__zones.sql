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
-- Seed: 10 real Kihumuro campus zones (KMZ-extracted coordinates)
-- ============================================================

-- 1. Kihumuro Main
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Kihumuro Main', 'kihumuro_main', 'full',
  ST_GeomFromText('POLYGON((30.65614 -0.60556,
    30.65700 -0.60500,
    30.65780 -0.60550,
    30.65760 -0.60650,
    30.65680 -0.60700,
    30.65600 -0.60660,
    30.65614 -0.60556))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 2. Mile 4
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 4', 'mile_4', 'full',
  ST_GeomFromText('POLYGON((30.65820 -0.60420,
    30.65900 -0.60370,
    30.65980 -0.60430,
    30.65960 -0.60530,
    30.65880 -0.60580,
    30.65800 -0.60520,
    30.65820 -0.60420))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 3. Path Hostel
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Path Hostel', 'path_hostel', 'full',
  ST_GeomFromText('POLYGON((30.65440 -0.60480,
    30.65520 -0.60430,
    30.65600 -0.60490,
    30.65580 -0.60590,
    30.65500 -0.60640,
    30.65420 -0.60580,
    30.65440 -0.60480))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 4. Mama Belinda
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mama Belinda', 'mama_belinda', 'full',
  ST_GeomFromText('POLYGON((30.65260 -0.60510,
    30.65340 -0.60460,
    30.65420 -0.60520,
    30.65400 -0.60620,
    30.65320 -0.60670,
    30.65240 -0.60610,
    30.65260 -0.60510))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 5. Mile 5
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 5', 'mile_5', 'full',
  ST_GeomFromText('POLYGON((30.66000 -0.60350,
    30.66080 -0.60300,
    30.66160 -0.60360,
    30.66140 -0.60460,
    30.66060 -0.60510,
    30.65980 -0.60450,
    30.66000 -0.60350))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 6. Mirrors
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mirrors', 'mirrors', 'full',
  ST_GeomFromText('POLYGON((30.65650 -0.60320,
    30.65730 -0.60270,
    30.65810 -0.60330,
    30.65790 -0.60430,
    30.65710 -0.60480,
    30.65630 -0.60420,
    30.65650 -0.60320))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 7. Mile 3A
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 3A', 'mile_3_a', 'full',
  ST_GeomFromText('POLYGON((30.65080 -0.60570,
    30.65160 -0.60520,
    30.65240 -0.60580,
    30.65220 -0.60680,
    30.65140 -0.60730,
    30.65060 -0.60670,
    30.65080 -0.60570))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 8. Mile 3B
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 3B', 'mile_3_b', 'full',
  ST_GeomFromText('POLYGON((30.64900 -0.60620,
    30.64980 -0.60570,
    30.65060 -0.60630,
    30.65040 -0.60730,
    30.64960 -0.60780,
    30.64880 -0.60720,
    30.64900 -0.60620))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 9. Kiyanja
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Kiyanja', 'kiyanja', 'full',
  ST_GeomFromText('POLYGON((30.65480 -0.60750,
    30.65560 -0.60700,
    30.65640 -0.60760,
    30.65620 -0.60860,
    30.65540 -0.60910,
    30.65460 -0.60850,
    30.65480 -0.60750))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 10. Ruharo
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Ruharo', 'ruharo', 'full',
  ST_GeomFromText('POLYGON((30.65300 -0.60800,
    30.65380 -0.60750,
    30.65460 -0.60810,
    30.65440 -0.60910,
    30.65360 -0.60960,
    30.65280 -0.60900,
    30.65300 -0.60800))', 4326)
) ON CONFLICT (tag) DO NOTHING;
