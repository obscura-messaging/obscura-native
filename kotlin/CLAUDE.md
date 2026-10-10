# ObscuraKit-Kotlin

Read [`docs/KIT_API.md`](../docs/KIT_API.md), the kit contract, first. It
defines the boundary, persist-then-ack, envelope identity, the inbox, entry
store and send. Do not restate its rules here.

`FriendDeviceInfo.registrationId` is diagnostic metadata, not an address.

## Quick Context

- **Server:** `obscura.barrelmaker.dev` (OpenAPI spec at `/openapi.yaml`)
- **Transport:** `obscura-proto` (shared submodule at `../proto/`).
- **Client contract:** `../protocol/` and `../docs/KIT_API.md`.
- **Sibling kit:** [`../swift`](../swift). It must agree with this one on the
  contract and the wire vectors, not on internal design.
- **Build:** `JAVA_HOME=/path/to/jdk-21 ./gradlew :lib:test`
- **Tests:** `src/test` runs without a network; `src/integrationTest` drives the
  public facade against a configured server. JUnit 5 ignores non-void `@Test`
  methods, so a body ending in `assertThrows(...)` needs a trailing `Unit`.

`obscura-client-web` is a throwaway proof-of-concept, not a reference implementation.

## Runtime boundaries

- **Transport:** `network/APIClient.kt`, `network/GatewayConnection.kt` — REST + WebSocket; the
  server is a blind relay.
- **Encrypted messaging:** `messaging/Messenger.kt`, `crypto/SignalStore.kt`, and the seven live
  client-to-client payload arms.
- **Durable app boundary:** `stores/InboxStore.kt` + `stores/EntryStore.kt` — an inbox of decrypted
  opaque bytes and a blind application entry store.

`ObscuraClient.kt` is the facade that wires these boundaries together.

## Key Patterns

- **Confined coroutines:** Each domain class uses `Dispatchers.Default.limitedParallelism(1)` — Kotlin equivalent of Swift Actors
- **Auto-session building:** `Messenger.queueMessage()` fetches prekey bundles and builds Signal sessions on demand
- **StateFlow for UI:** `connectionState` and `authState`. Friendship changes emit the payload-free
  `friendsChanged` wake event; hosts pull the current rows with `getFriends()`. Pending requests
  have status `pending_received`.
- **Inbound wake stream:** `incomingMessages` has exactly one app consumer; push draining observes
  receive activity without consuming it.

## Server API Endpoints Used

```
POST /v1/users              register
POST /v1/devices            provision device with Signal keys
POST /v1/sessions           login (with optional deviceId)
POST /v1/sessions/refresh   token refresh
DELETE /v1/sessions         logout
GET  /v1/users/{id}         fetch PreKey bundles
GET  /v1/devices            list devices
DELETE /v1/devices/{id}     delete device
POST /v1/devices/keys       upload/replace keys (takeover)
POST /v1/messages           send encrypted batch (protobuf)
POST /v1/gateway/ticket     WebSocket auth ticket
WS   /v1/gateway            WebSocket (EnvelopeBatch/AckMessage)
POST /v1/attachments        upload blob
GET  /v1/attachments/{id}   download blob
```

## Dependencies

libsignal-client (Signal Protocol JVM), protobuf-kotlin, SQLDelight (JVM SQLite), OkHttp, kotlinx-coroutines, org.json

## Critical Knowledge (read before making changes)

Hard-won lessons in `docs/knowledge/`. Read these before touching the codebase:

- [runTest vs runBlocking](docs/knowledge/critical_runtest_vs_runblocking.md) — WebSocket tests MUST use runBlocking (virtual time breaks OkHttp)
- [Server API quirks](docs/knowledge/critical_server_api_quirks.md) — password min 12, listDevices wraps in object, rate limiting, no /health
- [Signal session building](docs/knowledge/critical_signal_session_building.md) — encrypt() fails without session, ensureSession() pattern is critical
- [Multi-device queue draining](docs/knowledge/critical_multidevice_queue_draining.md) — befriend() fans out to ALL devices, tests must drain every queue
- [Generated naming](docs/knowledge/critical_protobuf_naming.md) — SQLDelight `data_` and protobuf/Okio ByteString ambiguity
- [Facade completeness](docs/knowledge/critical_facade_completeness.md) — supported user flows use the facade; adversarial wire tests are explicit exceptions
