-- Phase G3: User last known location + FCM token

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS last_known_lat  DOUBLE PRECISION NULL,
  ADD COLUMN IF NOT EXISTS last_known_lng  DOUBLE PRECISION NULL,
  ADD COLUMN IF NOT EXISTS last_geo        geography(Point, 4326) NULL,
  ADD COLUMN IF NOT EXISTS last_location_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS fcm_token       TEXT NULL;

CREATE INDEX IF NOT EXISTS ix_users_last_geo_gist ON users USING GIST (last_geo);
