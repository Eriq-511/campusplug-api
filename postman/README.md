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

- OTP-first registration flow:
   1) **Auth → Register Start (OTP-first)** (or alternate-location variant)
   2) Set `{{otpCode}}` from email
   3) **Auth → Register Verify OTP**
   4) **Auth → Register Set Password (sets {{token}})**
- Login flow:
   1) **Auth → Login (sets {{token}})**
- Forgot-password flow (OTP-only, no link):
   1) **Auth → Forgot Password**
   2) Set `{{otpCode}}` from email (in local/dev, Postman auto-captures `devOtp` when returned)
   3) **Auth → Reset Password** (uses `{{email}}`, `{{otpCode}}`, `{{newPassword}}`)
- After login, all secured requests will automatically use `Authorization: Bearer {{token}}`.
- Login also sets `{{publicUserId}}` for **Users → Get Public Profile**.
- Run **Listings → Create Listing (sets {{listingId}})** to populate `{{listingId}}`.
- Run **Conversations → Create/Get Conversation (sets {{conversationId}})** to populate `{{conversationId}}`.
- Run **Messages → Send Message (sets {{messageId}})** to populate `{{messageId}}`.
 For a full 2-user OTP flow, run **Conversation Flow (2-user OTP demo)** in order and set `{{sellerOtpCode}}` / `{{buyerOtpCode}}` from email before registration verify steps.

Defaults are configured in the environment for:

- `{{email}}`, `{{password}}`, `{{registrationNumber}}`
- `{{newPassword}}`
- `{{emailAlt}}`, `{{registrationNumberAlt}}`
- `{{sellerEmail}}`, `{{buyerEmail}}`, `{{sellerRegistrationNumber}}`, `{{buyerRegistrationNumber}}`
- `{{sellerOtpCode}}`, `{{buyerOtpCode}}`

Additional coverage included in the collection:

- **Auth → Register Verify OTP** (`/api/v1/auth/register/verify-otp`)
- **Auth → Register Set Password** (`/api/v1/auth/register/set-password`)
- **Auth → Forgot Password** (`/api/v1/auth/forgot-password`)
- **Auth → Reset Password** (`/api/v1/auth/reset-password`)
- **Users → Update Last Location** and **Update FCM Token**
- **Geo** (`/api/v1/geo/geocode`, `/api/v1/geo/reverse`)
- **Zones** (`/api/v1/zones`, `/api/v1/location/check`)
- **Browse** zone/feed endpoints (`/api/v1/listings/zone/{tag}`, `/count`, `/feed`)
- **Uploads** (`/api/v1/uploads/cloudinary/signature`)

## Notes

- OTP codes are **5 digits**. Set `{{otpCode}}` to the emailed code before running **Auth → Register Verify OTP** or **Auth → Reset Password**.
- Zone-testing defaults now target Kihumuro coordinates (`{{lat}}=-0.5950`, `{{lng}}=30.5970`, `{{zoneTag}}=kihumuro_main`) for easier geofence checks.
- Registration now requires a saved location: provide exactly one of `registeredLocation` or `alternateLocation`.
- When creating listings, location fields are optional; if omitted, the API uses the user's saved registered location, otherwise falls back to the alternate location.

- `imageId` is not auto-populated (attach-image response currently doesn’t expose it in a simple way for Postman scripts). If you remove an image, copy the id from the listing response and set `imageId` in the environment.
- WebSocket/STOMP testing isn’t included as a runnable request here; Postman can’t natively speak STOMP. Use a WS/STOMP client (or a small script) if you need to test `/ws`.
