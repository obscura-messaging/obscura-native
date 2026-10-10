# ObscuraKit-Kotlin

The **native Android/JVM platform layer** for the Obscura app (`obscura-pix`). It is not a
general-purpose framework, it has exactly one consumer, and it owes API stability to no one.

Read [`CLAUDE.md`](CLAUDE.md) before changing anything. The normative brief is
[`NATIVE_CONTRACT.md` §0 — the kit boundary](../docs/NATIVE_CONTRACT.md), with the app-facing
contract in [`KIT_API.md`](../docs/KIT_API.md).

**Why a native kit exists at all:** libsignal ships only as `libsignal-java` / `libsignal-swift` —
there is no supported shared core, so the Signal protocol must be implemented
per platform. Background push processing also cannot depend on a React Native
runtime. Those constraints justify native code; everything else belongs in the
app.

Merge, audience resolution, schemas, queries, expiry, and notification policy
belong in `obscura-pix`. Do not add ORM, CRDT, query, schema, or routing layers
to this kit; `KIT_API.md` §9 defines the boundary.

*(`obscura-client-web` is a throwaway proof-of-concept. It is **not** a reference implementation and
must not be treated as a porting target.)*

## The rule that governs this repo

> **If the kit reads it, it is a field in `client.proto`.
> If it is not in `client.proto`, the kit MUST NOT read it.**

## Quick Start

```kotlin
val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"))
client.register("alice", "mypassword123!")
client.connect()

// Befriend someone (the code is base64 JSON, QR-friendly — see FriendCode.kt)
client.addFriendByCode(theirCode)

// SEND: the caller names the recipients. The kit fans out to every device of every
// listed userId plus this user's own other devices, and resolves no audience of its
// own (SPEC §0.4). `payload` is opaque bytes the kit never parses.
client.send(
    recipientUserIds = listOf(friendUserId),
    modelKey = "pix",
    entryId = java.util.UUID.randomUUID().toString(),
    payload = """{"caption":"hello"}""".toByteArray(),
)

// RECEIVE: a durable inbox, drained by the app. peek / consume / discard / depth,
// and there is no insert — the kit is the only writer.
for (row in client.inbox.peek(limit = 50)) {
    // Sender identity comes from transport/session metadata, never from the payload.
    // row.payload is the bytes we sent above.
    handle(row)
}
client.inbox.consume(processedIds)

// STORE: a blind key/value table for whatever the app made of them. No merge,
// expiry, or query API — the app decides who wins.
client.entries.put(
    "pix",
    StoredEntry(
        id = entryId,
        data = json,
        sentAt = t,
        authorDeviceId = d,
        localMetadata = """{"uploadState":"complete"}""", // local-only; never sent
    ),
)
client.entries.all("pix")

// Aggregate friend changes are payload-free wake-ups. Pull canonical rows after each wake.
client.friendsChanged.collect {
    render(client.getFriends())
}
val debugLines = client.getDebugLog() // pull-only; never emitted as live events

// Ephemeral typing indicators (not persisted, auto-expire after 3s)
client.sendTyping(listOf(friendUserId), contextId, TypingState.STARTED)
client.observeTyping(contextId)  // Flow<List<String>>
```

See [docs/AUTHENTICATION.md](docs/AUTHENTICATION.md) for auth and device linking, and
[docs/FRIEND_CODE.md](docs/FRIEND_CODE.md) for the friend-code format.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  obscura-pix (merge, audience, all app semantics)    │
├──────────────────────────────────────────────────────┤
│  ObscuraClient facade                                │
╞══════════════════════════════════════════════════════╡
│  Durable boundary: inbox + entries + friends/devices │
│                    payload bytes stay opaque          │
╞══════════════════════════════════════════════════════╡
│  Encrypted messaging: Signal + client.proto          │
╞══════════════════════════════════════════════════════╡
│  Transport: WebSocket + REST (blind relay server)    │
╞══════════════════════════════════════════════════════╡
│  SQLDelight (Signal keys, friends, inbox, entries)   │
└──────────────────────────────────────────────────────┘
```

## What this kit does

The unit suite runs without a network. The integration suite exercises the
public facade against a configured `obscura-server`.

- **Signal Protocol** — identity, prekeys, sessions, encrypt/decrypt. Sessions are addressed by
  **device UUID** (SPEC §0.10): the inbound session comes from `Envelope.sender_device_id`, prekey
  bundles are selected by device UUID with no fallback, and `registrationId` addresses nothing.
- **Persist-then-ack receive loop** (SPEC §0.9) — an ack is a DELETE on the server, so the kit acks
  only what it has durably written. A decrypt failure, a rate-limited sender, or a failed write all
  leave the message on the server to redeliver.
- **The durable inbox** (`KIT_API.md` §3) — `peek` / `consume` / `discard` / `depth`, deduped on
  `envelope_id` while a row remains pending.
- **The entry store** (§8.1) — `put` / `all` / `erase` over opaque JSON. Three methods, no fourth.
- **Friend graph** — request/accept, with device lists learned from DEVICE_ANNOUNCE.
- **Login** — `login()` returns a `LoginScenario`; only `EXISTING_DEVICE` authenticates (`KIT_API.md` §10).
- **Device provisioning and linking** — `loginAndProvision()` → `PENDING_APPROVAL` (when another device can approve) →
  QR/link-code approval, which carries the own-device list and friends export.
- **Transport** — REST + gateway WebSocket with auto-reconnect and token refresh; the offline queue
  is the server's, not ours.
- **Attachment crypto** — upload/download with an AES key shipped over Signal.
- **Push-wake drain** — `processPendingMessages(timeoutMs)` returns one opaque total without
  consuming the app's event stream. Zero also represents failure to connect after the bounded
  retries, so it is not proof that the server queue is empty. Notification policy stays in the app;
  the kit never posts one.
- **Ephemeral signals** — typing indicators, in memory only, throttled to 2s and expiring after 3s.
  The caller supplies explicit recipients and a typed STARTED/STOPPED state; the context id is opaque.

## What this kit deliberately does not do

- **No merge, no CRDT, no TTL, no query DSL, no schema, no audience resolution.** These are
  `obscura-pix` responsibilities. `EntryStore.all(model)` returns everything and the app filters.
- **No OS notifications**, no UI, no application field names. `modelKey` is opaque throughout.
- **No eviction policy on the inbox.** Rows leave only by an explicit `consume`/`discard` from the
  app, or by the security carve-out in a device wipe (§3.3 rule 2).

## Cross-kit contract

`ObscuraKit-swift` must agree with this kit on the **wire**
([`protocol/conformance/wire.json`](../protocol/conformance/wire.json)). Broader implementation parity is not implied.

## Build & Test

```bash
export JAVA_HOME=/path/to/jdk-21

./gradlew :lib:test                              # fast, no network
./gradlew :lib:integrationTest                   # server-dependent
./gradlew :lib:koverHtmlReport                   # coverage report
```

The integration suite targets `https://obscura.barrelmaker.dev` by default but honors
`OBSCURA_TEST_API` — CI points it at a containerized `obscura-server` (rate limits disabled) so the
suite runs on every PR without touching prod. Each integration test is gated on
`assumeTrue(checkServer())`, so it skips-not-fails when no server is reachable. It also needs the
server *correctly configured*: seed the MinIO `test-bucket` and raise the auth rate limit, or you
get ~63 environmental failures (HTTP 429/500) that are not code failures.

**A non-void `@Test` is silently ignored by JUnit 5.** This has bitten twice. If a test body ends in
`assertThrows(...)`, add a trailing `Unit`.

## Docs

- [Authentication](docs/AUTHENTICATION.md) — register, login, device linking, session restore
- [Friend codes](docs/FRIEND_CODE.md) — the QR/paste format
- [`docs/knowledge/`](docs/knowledge) — hard-won lessons; read before touching the codebase

## Dependencies

- `org.signal:libsignal-client` — Signal Protocol
- `com.google.protobuf:protobuf-kotlin` — wire format
- `app.cash.sqldelight:sqlite-driver` — persistence
- `com.squareup.okhttp3:okhttp` — HTTP + WebSocket
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — async
- `org.jetbrains.kotlinx:kotlinx-serialization-json` — JSON in the crypto/backup helpers
- `org.json:json` — JSON parsing

## Server

- **API:** https://obscura.barrelmaker.dev
- **Spec:** https://obscura.barrelmaker.dev/openapi.yaml
