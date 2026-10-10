# Claude Code Context

Read [`docs/KIT_API.md`](../docs/KIT_API.md), the kit contract, first. It
defines the boundary, persist-then-ack, envelope identity, the inbox, entry
store, send and the known gaps. Do not restate its rules here.

`ObscuraSchema` owns the current pre-release baseline. Until the first public
release, schema changes require clearing app data rather than carrying prototype
migrations. After release, never edit an applied migration.

## Current constraints

- No Notification Service Extension exists. Shared database, key, and session
  plumbing exists but is not device-verified; the current APNs payload cannot
  launch an NSE. See `docs/NSE_PREREQUISITES.md`.
- `APP_ENTRY` has one durable receive write: `inbox.put`.

`obscura-client-web` is a throwaway proof of concept, not a reference
implementation.

@README.md
@docs/PITFALLS.md
@docs/MESSAGE_FLOW.md
@docs/AGENT_NOTES.md

## Server

- **API:** https://obscura.barrelmaker.dev
- **OpenAPI Spec:** https://obscura.barrelmaker.dev/openapi.yaml
- **Server Repo:** https://github.com/obscura-messaging/obscura-server

All smoke/scenario tests run against the live server.

### Rate Limits (per-instance, 3 instances load balanced)
- **General endpoints:** 10 req/s sustained, 20 req/s burst
- **Auth endpoints** (register, login, refresh): 1 req/s sustained, 3 req/s burst
- Use `await rateLimitDelay()` (100ms) between general calls
- Use `await authRateLimitDelay()` (1000ms) between auth calls
- Both configurable via `SERVER_REQUEST_DELAY_MS` and `AUTH_REQUEST_DELAY_MS` in `Constants.swift`

## Server API Quick Reference

### Auth Tokens
- **User-Scoped JWT:** From POST `/v1/users` or `/v1/sessions`. For device provisioning.
- **Device-Scoped JWT:** Includes `deviceId` claim. Required for messaging, keys, gateway.
- **Refresh Token:** Rotation invalidates old token. Use POST `/v1/sessions/refresh`.
- **WebSocket Ticket:** Single-use from POST `/v1/gateway/ticket`, pass as query param to `/v1/gateway`.

### Endpoints
```
POST   /v1/users                 Register (no auth, JSON)
POST   /v1/sessions              Login (no auth, JSON)
POST   /v1/sessions/refresh      Refresh tokens (no auth, JSON)
DELETE /v1/sessions              Logout (Bearer)

POST   /v1/devices               Provision device with keys (Bearer, JSON)
GET    /v1/devices               List devices (Bearer)
GET    /v1/devices/{id}          Get device (Bearer)
PUT    /v1/devices/{id}          Update device (Bearer)
DELETE /v1/devices/{id}          Delete device + cascade (Bearer)

POST   /v1/devices/keys          Upload prekeys / device takeover (Device-Scoped, JSON)
GET    /v1/users/{userId}        Fetch prekey bundles (Device-Scoped, JSON)

POST   /v1/messages              Send batch (Device-Scoped, Protobuf, Idempotency-Key header)

POST   /v1/gateway/ticket        Get WebSocket ticket (Device-Scoped)
GET    /v1/gateway               WebSocket connect (ticket query param, Protobuf frames)

POST   /v1/attachments           Upload encrypted blob (Bearer, binary)
GET    /v1/attachments/{id}      Download (Bearer, binary, ETag caching)

PUT    /v1/push-tokens           Register APNS/FCM token (Device-Scoped, JSON)
```

### Key Response Shapes

**AuthResponse:** `{ token, refreshToken, expiresAt, deviceId? }`

**PreKeyBundleResponse (from GET /v1/users/{userId}):**
```json
[{
  "deviceId": "uuid",
  "registrationId": 12345,
  "identityKey": "base64",
  "signedPreKey": { "keyId": 1, "publicKey": "base64", "signature": "base64" },
  "oneTimePreKey": { "keyId": 1, "publicKey": "base64" }
}]
```

**CreateDeviceRequest (POST /v1/devices):**
```json
{
  "name": "iPhone",
  "identityKey": "base64",
  "registrationId": 12345,
  "signedPreKey": { "keyId": 1, "publicKey": "base64", "signature": "base64" },
  "oneTimePreKeys": [{ "keyId": 1, "publicKey": "base64" }]
}
```

### Critical Implementation Notes
- `POST /v1/messages` requires `Idempotency-Key` header (UUID) and `Content-Type: application/x-protobuf`
- Device takeover: if `POST /v1/devices/keys` includes a different `identityKey`, server replaces all keys and disconnects existing sessions
- Backup uses optimistic locking: `If-None-Match: *` for initial upload, `If-Match: <etag>` for updates
- Signal keys: 33-byte public (0x05 prefix + 32 bytes), 32-byte private, 64-byte XEdDSA signatures

## Build & Test

```bash
./dev.sh build
./dev.sh test
./dev.sh test --filter CoreFlowTests
```

Native builds on macOS 13+. No Docker. `dev.sh` sets the Swift 6.1 toolchain and `LIBRARY_PATH` for libsignal FFI.
