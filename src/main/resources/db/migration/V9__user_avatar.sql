-- V9: Add avatar (profile picture) columns to users table
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS avatar_url        TEXT,
    ADD COLUMN IF NOT EXISTS avatar_public_id  TEXT;
