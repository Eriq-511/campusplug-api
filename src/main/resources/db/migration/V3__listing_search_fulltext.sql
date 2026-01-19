-- Phase 7: Full-text search support (GIN index) for listings

-- Generated search document for title + description
ALTER TABLE listings
  ADD COLUMN IF NOT EXISTS search_tsv tsvector
  GENERATED ALWAYS AS (
    to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(description, ''))
  ) STORED;

CREATE INDEX IF NOT EXISTS ix_listings_search_tsv_gin
  ON listings
  USING GIN (search_tsv);
