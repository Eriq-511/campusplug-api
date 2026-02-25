# Postman

This folder contains importable Postman assets for testing the CampusPlug API.

## Import

1) Open Postman
2) Import the collection:
   - `postman/CampusPlug API.postman_collection.json`
3) Import the environment:
   - `postman/CampusPlug Local.postman_environment.json`
4) Select the `CampusPlug Local` environment

## How to use

- Run **Auth → Register** (or **Auth → Register (alternate location fallback)**) then **Auth → Login (sets {{token}})**.
- After login, all secured requests will automatically use `Authorization: Bearer {{token}}`.
- Run **Listings → Create Listing (sets {{listingId}})** to populate `{{listingId}}`.
- Run **Conversations → Create/Get Conversation (sets {{conversationId}})** to populate `{{conversationId}}`.
- Run **Messages → Send Message (sets {{messageId}})** to populate `{{messageId}}`.

## Notes

- Registration now requires a saved location: provide exactly one of `registeredLocation` or `alternateLocation`.
- When creating listings, location fields are optional; if omitted, the API uses the user's saved registered location, otherwise falls back to the alternate location.

- `imageId` is not auto-populated (attach-image response currently doesn’t expose it in a simple way for Postman scripts). If you remove an image, copy the id from the listing response and set `imageId` in the environment.
- WebSocket/STOMP testing isn’t included as a runnable request here; Postman can’t natively speak STOMP. Use a WS/STOMP client (or a small script) if you need to test `/ws`.
