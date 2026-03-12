-- Add optional listing reference to individual messages for context in unified conversations
-- This allows users to show which product they're discussing within a unified chat thread

ALTER TABLE messages 
ADD COLUMN referenced_listing_id BIGINT;

-- Add foreign key constraint (optional, but good for data integrity)
ALTER TABLE messages 
ADD CONSTRAINT fk_messages_referenced_listing 
FOREIGN KEY (referenced_listing_id) REFERENCES listings(id) ON DELETE SET NULL;

-- Index for performance when querying messages by referenced listing
CREATE INDEX idx_messages_referenced_listing ON messages(referenced_listing_id);