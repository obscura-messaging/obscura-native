# ObscuraKit (Swift)

Swift package for the `obscura-pix` iOS bridge. Behaviour is defined by
[`docs/KIT_API.md`](../docs/KIT_API.md); this file covers only Swift specifics.
No Notification Service Extension exists yet; see
[`docs/NSE_PREREQUISITES.md`](docs/NSE_PREREQUISITES.md).

## Build and test

macOS only: GRDB's bundled SQLCipher needs `CommonCrypto`, so the package does
not build on Linux. Needs Xcode 16+ (tools 6.0); targets macOS 13 and iOS 16.

```bash
just swift-build                                 # bootstrap libsignal, then ./dev.sh build
just swift-unit                                  # UnitTests target, offline
just swift-integration http://localhost:3000     # ScenarioTests target, needs a server
cd swift && ./dev.sh test --filter CoreFlowTests # one suite (after a first just run)
```

- `dev.sh` runs `xcrun swift` with `LIBRARY_PATH` pointing at the libsignal
  FFI. It needs `vendored/libsignal`, which only
  `scripts/bootstrap-libsignal.sh [host|ios-sim|ios-device]` creates (the
  `just` recipes run it for `host`).
- libsignal is pinned to v0.40.0. Newer releases require Kyber prekeys in every
  `PreKeyBundle`, and the server has none. Changing the pin means updating
  `LIBSIGNAL_REF` in the bootstrap script and `.github/workflows/swift.yml`.
- Protobuf bindings in `Sources/ObscuraKit/Proto/` are generated and checked in;
  regenerate with `scripts/gen-proto.sh`. Generated types are `internal`.
- `ScenarioTests` default to `https://obscura.barrelmaker.dev`
  (`OBSCURA_TEST_API` overrides) and fail, not skip, without a server. Server
  setup: [`CONTRIBUTING.md`](../CONTRIBUTING.md#integration-tests).
- The `Dockerfile` predates the macOS-only requirement and does not build the
  package.

## Facade

```swift
let client = try ObscuraClient(apiURL: url)                       // in-memory
let client = try ObscuraClient(apiURL: url, dataDirectory: dir,   // file-backed
                               userId: userId, keychainAccessGroup: nil)

try await client.send(to: userIds, modelKey: m, entryId: id, sentAt: t, payload: data)
try await client.inbox.peek(limit: 50); try await client.inbox.consume(ids)
try await client.inbox.discard(ids, reason: r); try await client.inbox.depth()
try await client.entries.put(model: m, entry: StoredEntry(id:data:sentAt:authorDeviceId:localMetadata:))
try await client.entries.all(model: m); try await client.entries.erase(model: m, id: id)
try await client.uploadAttachment(data)   // (id, contentKey, nonce)
try await client.downloadDecryptedAttachment(id: id, contentKey: k, nonce: n)
await client.sendTyping(to: userIds, contextId: c, state: .started)
for await names in client.observeTyping(contextId: c).values { }
await client.processPendingMessages(timeout: 25)
for await event in client.observeEvents() { }   // ObscuraEvent
```

- The file-backed client stores everything in `dataDirectory/obscura.sqlite`
  with file protection `completeUntilFirstUserAuthentication`. It is
  SQLCipher-encrypted only when `userId` is passed; the key is created in the
  Keychain (`keychainAccessGroup` shares it with an extension).
- `ObscuraEvent`: `friendsChanged`, `connectionChanged`, `authChanged`,
  `messageReceived(model:)`, `typingChanged`, `authFailed(reason:)`
  (token refresh exhausted).
- `befriend(_:username:)`, `acceptFriend(_:)`, `addFriendByCode(_:)` (strips
  soft hyphens, accepts URL-safe Base64), `friendCode()` (Base64 of
  `{"u", "n"}`).
- Typing: sends throttled to one per 2 s per context and state; received state
  expires after 5 s, and signals older than 5 s are ignored.
- Kit methods sleep between server calls: `rateLimitDelay()` (100 ms,
  `SERVER_REQUEST_DELAY_MS`) and `authRateLimitDelay()` (1000 ms,
  `AUTH_REQUEST_DELAY_MS`), both in `Network/Constants.swift`.
- Set `logger` (`ObscuraLogger`) to receive security events: decrypt and ack
  failures, identity-key changes, token refresh failures, frame parse errors.
  The default is `PrintLogger`.

## Auth and devices

```swift
switch try await client.login(username, password) {
case .existingDevice: try await client.connect()
case .newDevice: try await client.loginAndProvision(username, password, deviceName: name)
case .deviceMismatch: try await client.wipeDevice(); try await client.loginAndProvision(username, password, deviceName: name)
case .invalidCredentials: showError()
}
```

- `AuthState`: `.loggedOut`, `.pendingApproval`, `.authenticated`. Swift cannot
  yet leave `.pendingApproval` (see known gaps in `KIT_API.md`).
- **Linking.** The new device shows `generateLinkCode()` (Base58 JSON
  `{deviceId, challenge, timestamp}`, valid 5 minutes; a future timestamp counts
  as fresh). The existing device calls `validateAndApproveLink(_:)`, which sends
  `DEVICE_LINK_APPROVAL` then `DEVICE_ANNOUNCE`.
- **Sessions.** Set `sessionStorage` (e.g. `UserDefaultsSessionStorage`) before
  `register`/`login`; the kit saves on becoming authenticated and on connect.
  A token refresh only calls `onSessionChanged`: call `persistSession()` there,
  because refresh tokens are single-use and a stale stored one gets a 401.
  `restorePersistedSession()` restores, refreshes and connects; it throws when
  nothing usable is stored.
- `logout()` disconnects and forgets credentials; local data and stored
  session stay. `fullLogout()` also stops background tasks, clears typing
  state and clears `sessionStorage`. `wipeDevice()` is `logout()` plus deleting
  all local kit data.
- `ensureConnected()` is safe on every foreground resume: it connects only when
  authenticated and fully disconnected.

## Code map (`Sources/ObscuraKit/`)

| Path | Role |
|---|---|
| `ObscuraClient.swift` | Facade and envelope loop (decrypt, route, persist, ack). |
| `Network/` | `APIClient`, `GatewayConnection`, pacing constants. |
| `Messaging/Messenger.swift` | Signal encrypt/decrypt and session building. |
| `Stores/` | GRDB-backed inbox, entries, friends, devices. |
| `Storage/ObscuraSchema.swift` | The one schema migration. |
| `Crypto/` | `PersistentSignalStore`, attachment AES-GCM, database key. |
| `Devices/DeviceLink.swift` | Link codes. |
| `Wire/` | `WireCodec`, payload disposition, typing. |

## Pitfalls

- **Schema.** `ObscuraSchema` is the pre-release baseline. Until the first
  public release, change it in place and require clearing app data; after
  release, add migrations and never edit an applied one.
- **Reactive layer.** Store observation uses GRDB `ValueObservation`. Do not
  add Combine, `@Published`, or a second mechanism.
- **Tests use `ObscuraTestClient`,** a thin wrapper over `ObscuraClient`. Keep
  them in step when a facade signature changes. Adversarial wire tests send
  raw protobuf through `sendRaw`.
- **Buffered wake-ups.** `waitForMessage()` (internal, tests only) reads a
  1000-entry buffer filled by the envelope loop. Do not replace it with a fresh
  `AsyncStream` subscription; messages processed before the subscription would
  be missed.
- **Disconnect clients** at the end of a test so the envelope loop stops.
- **Compare secrets** with `constantTimeEqual`, never `Data ==`.
- `ProtocolAddress` is always `(deviceUUID, 1)`.
