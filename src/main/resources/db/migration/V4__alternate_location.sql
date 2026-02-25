-- Phase 11 (partial): Support alternate (fallback) saved location on users

-- Add alternate location fields for cases where registered location cannot be resolved to a valid label.
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS alternate_location_text TEXT NULL;

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS alternate_geo geography(Point, 4326) NULL;
