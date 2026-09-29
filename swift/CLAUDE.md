# Claude Code Context

## Read this before changing anything

Read these first:

- [`NATIVE_CONTRACT.md` §0 — The kit boundary](../docs/NATIVE_CONTRACT.md) — normative, and the
  section that decides every "should the kit do this?" argument.
- [`KIT_API.md`](../docs/KIT_API.md) — the app-facing surface this kit
  implements: the inbox (§3), payload classification (§4), `send` (§5), the entry store (§8.1), and
  §9's rule that the entry store does not grow a query API.
- [`HISTORY.md`](../docs/HISTORY.md) — non-normative
  migration history.

The current app-facing surface is
`client.send(to:modelKey:entryId:sentAt:payload:)`, the durable
`client.inbox`, and opaque `client.entries` storage. Merge, audience resolution,
schemas, queries, expiry, and notification policy live in `obscura-pix`.

`ObscuraSchema` owns the current pre-release baseline. Until the first public
release, schema changes require clearing app data rather than carrying prototype
migrations. After release, never edit an applied migration.

The rule that governs this repo:

> **If the kit reads it, it is a field in `client.proto`.
> If it is not in `client.proto`, the kit MUST NOT read it.**

**Do not add an ORM, CRDT layer, query builder, audience/routing engine, or
schema parser.** Re-check `NATIVE_CONTRACT.md` §0 and the shipping app before expanding the
entry store beyond `KIT_API.md` §8.1.

## Current constraints

- No Notification Service Extension exists. Shared database, key, and session
  plumbing exists but is not device-verified; the current APNs payload cannot
  launch an NSE. See `docs/NSE_PREREQUISITES.md`.
- This kit sends `DEVICE_LINK_APPROVAL` but has no receive handler. The arm is
  logged, dropped, and acknowledged so it cannot wedge the queue. Kotlin handles
  it, so linked-device behavior differs by platform.
- Device announcements have no replay protection.
- Linked devices receive the friend graph at link time but do not learn friends
  added later.
- `APP_ENTRY` has one durable receive write: `inbox.put`. Persistence errors
  propagate and skip acknowledgement (`NATIVE_CONTRACT.md` §0.9).

`obscura-client-web` is a throwaway proof of concept, not a porting target or
normative implementation.

## Project Overview

ObscuraKit — the **native iOS platform layer** for the Obscura app (`obscura-pix`). Not a
general-purpose framework; it has one consumer and owes API stability to no one.

It exists natively because libsignal has no supported shared core and
background push processing cannot depend on a React Native runtime. Everything
else belongs in the app.

It must agree with ObscuraKit-Kotlin on the **wire** (`../protocol/conformance/wire.json`) and nothing more.

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

## Reference implementations

There is no porting reference. This kit is written against
`docs/NATIVE_CONTRACT.md` and `protocol/conformance/`, not against another
implementation.

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
