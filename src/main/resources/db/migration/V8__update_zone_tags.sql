-- V8: Align zone tags to match GeoJSON KIhumuro_Zones source
-- Old abbreviated tags → canonical tags matching the GeoJSON properties

-- Drop FK constraints that reference zones.tag so we can rename safely
ALTER TABLE user_zones DROP CONSTRAINT IF EXISTS user_zones_zone_tag_fkey;
ALTER TABLE listings   DROP CONSTRAINT IF EXISTS listings_zone_tag_fkey;

-- Rename zone tags to match GeoJSON
UPDATE zones SET tag = 'kihumuro_zone'    WHERE tag = 'kihumuro_main';
UPDATE zones SET tag = 'mile_4_zone'      WHERE tag = 'mile_4';
UPDATE zones SET tag = 'path_hostel_zone' WHERE tag = 'path_hostel';
UPDATE zones SET tag = 'mama_belinda_zone'WHERE tag = 'mama_belinda';
UPDATE zones SET tag = 'mile_5_zone'      WHERE tag = 'mile_5';
UPDATE zones SET tag = 'mirrors_zone'     WHERE tag = 'mirrors';
UPDATE zones SET tag = 'mile_3_zone_a'    WHERE tag = 'mile_3_a';
UPDATE zones SET tag = 'mile_3_zone_b'    WHERE tag = 'mile_3_b';
UPDATE zones SET tag = 'kiyanja_zone'     WHERE tag = 'kiyanja';
UPDATE zones SET tag = 'ruharo_zone'      WHERE tag = 'ruharo';

-- Propagate new tags to referencing tables
UPDATE user_zones SET zone_tag = 'kihumuro_zone'     WHERE zone_tag = 'kihumuro_main';
UPDATE user_zones SET zone_tag = 'mile_4_zone'       WHERE zone_tag = 'mile_4';
UPDATE user_zones SET zone_tag = 'path_hostel_zone'  WHERE zone_tag = 'path_hostel';
UPDATE user_zones SET zone_tag = 'mama_belinda_zone' WHERE zone_tag = 'mama_belinda';
UPDATE user_zones SET zone_tag = 'mile_5_zone'       WHERE zone_tag = 'mile_5';
UPDATE user_zones SET zone_tag = 'mirrors_zone'      WHERE zone_tag = 'mirrors';
UPDATE user_zones SET zone_tag = 'mile_3_zone_a'     WHERE zone_tag = 'mile_3_a';
UPDATE user_zones SET zone_tag = 'mile_3_zone_b'     WHERE zone_tag = 'mile_3_b';
UPDATE user_zones SET zone_tag = 'kiyanja_zone'      WHERE zone_tag = 'kiyanja';
UPDATE user_zones SET zone_tag = 'ruharo_zone'       WHERE zone_tag = 'ruharo';

UPDATE listings SET zone_tag = 'kihumuro_zone'     WHERE zone_tag = 'kihumuro_main';
UPDATE listings SET zone_tag = 'mile_4_zone'       WHERE zone_tag = 'mile_4';
UPDATE listings SET zone_tag = 'path_hostel_zone'  WHERE zone_tag = 'path_hostel';
UPDATE listings SET zone_tag = 'mama_belinda_zone' WHERE zone_tag = 'mama_belinda';
UPDATE listings SET zone_tag = 'mile_5_zone'       WHERE zone_tag = 'mile_5';
UPDATE listings SET zone_tag = 'mirrors_zone'      WHERE zone_tag = 'mirrors';
UPDATE listings SET zone_tag = 'mile_3_zone_a'     WHERE zone_tag = 'mile_3_a';
UPDATE listings SET zone_tag = 'mile_3_zone_b'     WHERE zone_tag = 'mile_3_b';
UPDATE listings SET zone_tag = 'kiyanja_zone'      WHERE zone_tag = 'kiyanja';
UPDATE listings SET zone_tag = 'ruharo_zone'       WHERE zone_tag = 'ruharo';

-- Restore FK constraints
ALTER TABLE user_zones ADD CONSTRAINT user_zones_zone_tag_fkey
    FOREIGN KEY (zone_tag) REFERENCES zones(tag) ON DELETE CASCADE;

ALTER TABLE listings ADD CONSTRAINT listings_zone_tag_fkey
    FOREIGN KEY (zone_tag) REFERENCES zones(tag) ON DELETE SET NULL;
