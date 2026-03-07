-- Initial schema placeholder.
-- Phase 2 will add the full schema from planning.md.

DO $$
BEGIN
	BEGIN
		CREATE EXTENSION IF NOT EXISTS postgis;
	EXCEPTION
		WHEN insufficient_privilege THEN
			RAISE EXCEPTION 'PostGIS extension is required. Enable PostGIS on your database (Neon: enable the postgis extension for this database/branch), then restart the app.';
	END;
END$$;
