-- V7: Remove buyer/seller role language from the conversations table.
--
-- CampusPlug is a platform-agnostic conduit for peer-to-peer contact. The
-- platform never processes payments or holds escrow; a "trade" is arranged
-- off-platform through messaging. No user is permanently a buyer or seller —-
-- the same student can post a listing (poster) in one conversation and inquire
-- about someone else's listing (inquirer) in another.
--
-- Old name          → New name
-- buyer_user_id     → inquirer_user_id  (student who initiated the chat)
-- seller_user_id    → poster_user_id    (student who posted the listing)

ALTER TABLE conversations RENAME COLUMN buyer_user_id  TO inquirer_user_id;
ALTER TABLE conversations RENAME COLUMN seller_user_id TO poster_user_id;

ALTER TABLE conversations
    RENAME CONSTRAINT ck_conversations_buyer_seller_diff
    TO ck_conversations_participants_diff;

ALTER TABLE conversations
    RENAME CONSTRAINT ux_conversations_unique
    TO ux_conversations_listing_inquirer_poster;

ALTER INDEX ix_conversations_buyer  RENAME TO ix_conversations_inquirer;
ALTER INDEX ix_conversations_seller RENAME TO ix_conversations_poster;
