# ObscuraKit (Swift)

The **native iOS platform layer** for the Obscura app (`obscura-pix`). Not a general-purpose
framework; one consumer, no API-stability obligation.

The kit contract, including the boundary and current known gaps, is
[`docs/KIT_API.md`](../docs/KIT_API.md).

No Notification Service Extension exists; the remaining work is documented in
[`docs/NSE_PREREQUISITES.md`](docs/NSE_PREREQUISITES.md).

## What it does

Encryption, device fan-out, and a durable inbox. The app names the recipients, supplies opaque
bytes, and decides what those bytes mean.

```swift
// Send: the caller names the audience. The kit resolves none of its own.
try await client.send(
    to: [bobUserId], modelKey: "story", entryId: "story_123", payload: jsonBytes)

// Receive: peek → decide → write → consume. An ack is a DELETE, so the row is the only copy
// until the app takes it.
for row in try await client.inbox.peek(limit: 100) {
    try await client.entries.put(model: row.modelKey!, entry: merged(row))
}
try await client.inbox.consume(ids)
```

The developer never touches protobufs, Signal sessions, or WebSocket frames — and the kit never
touches the meaning of a payload.

## Architecture

```
YOUR APP
  ↕
ObscuraClient (facade)
  ↕
Layer 3: Inbox + entry store + Infrastructure (friends, devices)
  ↕
Layer 2: Signal Protocol (encrypt/decrypt, sessions, keys)
  ↕
Layer 1: Transport (WebSocket + REST, protobuf frames)
  ↕
Storage: GRDB/SQLite (SQLCipher encrypted at rest)
```

Friends and Devices are infrastructure — they are how the kit addresses devices and resolves a
sender's display name. They are **not** how it picks an audience; the caller does that. Everything
else (messages, stories, profiles, settings) is application content the kit stores as opaque bytes.

## API

```swift
// Auth
try await client.register(username, password)
let scenario = try await client.login(username, password) // LoginScenario; see docs/KIT_API.md
try await client.connect()

// Friends
try await client.befriend(userId)
try await client.acceptFriend(userId)
let friends = await client.getFriends()

// Aggregate friend events are payload-free wake-ups; pull canonical rows after each one.
for await event in client.observeEvents() {
    if case .friendsChanged = event {
        render(await client.getFriends())
    }
}
let debugLines = client.getDebugLog() // debug output is pull-only, never a live event

// Entries — send, receive, store. modelKey and payload are opaque to the kit.
try await client.send(to: [userId], modelKey: "story", entryId: id, payload: bytes)
let rows = try await client.inbox.peek(limit: 100)
try await client.inbox.consume(rows.map(\.id))
try await client.entries.put(model: "story", entry: entry)
let all = try await client.entries.all(model: "story")

// StoredEntry.localMetadata is an optional opaque local-only sidecar. It is persisted by
// EntryStore but never serialized into AppEntry or sent to another device.

// Ephemeral signals (typing indicators — not persisted, dropped rather than inboxed)
await client.sendTyping(to: [userId], contextId: contextId, state: .started)
for await who in client.observeTyping(contextId: contextId).values { ... }

// Device linking (QR/code approval, enforced for new devices)
let code = client.generateLinkCode()
try await existingClient.validateAndApproveLink(code)
```

## What works

The offline unit suite covers wire conformance; scenario tests exercise a
server. Both jobs run on macOS because GRDB's bundled SQLCipher requires
`CommonCrypto` (see `docs/PITFALLS.md`).

The two kits prove the shared wire mappings with
`../protocol/conformance/wire.json`. No test runs the two implementations directly against
each other, so broader behavioral interoperability is not claimed.

- Register, login, friend handshake, encrypted messaging
- Entries: send to a caller-named audience, receive into a durable inbox, store and read back
- Persist-then-ack: a failed durable write skips the ack, so the server redelivers
- Dedupe while pending: `envelope_id UNIQUE` + `INSERT OR IGNORE`
- Offline/reconnect: the server queues, and the inbox absorbs the duplicates that produces
- Attachments: encrypt, upload, download, decrypt — the bytes path, kept
- Device linking: QR/code generation, validation, approval flow
- Ephemeral signals: caller-addressed typed STARTED/STOPPED indicators, in-memory only
- Self-sync: own *other* devices get your content too, and the sending device does not
- One current pre-release schema owned by `ObscuraSchema`
- Cross-platform: the **wire format** interoperates with Android

## What doesn't work yet

- Group-targeted sync has no server test
- Entry expiry is not implemented on either platform
- A linked device learns the friend graph at link time only, not afterwards

## Build & Test

```bash
./dev.sh build
./dev.sh test
./dev.sh test --filter CoreFlowTests
```

Requires macOS 13+, Xcode 16+. `dev.sh` sets `LIBRARY_PATH` for the vendored libsignal Rust FFI.

## Dependencies

- `signalapp/libsignal` v0.40.0 — Signal Protocol (vendored, Rust FFI)
- `apple/swift-protobuf` — protobuf codegen
- `groue/GRDB.swift` — SQLite persistence + ValueObservation (SQLCipher fork)
- `CryptoKit` — SHA-256, HMAC (system)
- `URLSessionWebSocketTask` — WebSocket (system)

## Docs

- [docs/CLIENT_API.md](docs/CLIENT_API.md) — Auth, friends, devices, and device linking
- [docs/MESSAGE_FLOW.md](docs/MESSAGE_FLOW.md) — Send/receive data flow diagrams
- [docs/PITFALLS.md](docs/PITFALLS.md) — Gotchas that waste hours

## Server

- **API:** https://obscura.barrelmaker.dev
- **Server Repo:** https://github.com/obscura-messaging/obscura-server
