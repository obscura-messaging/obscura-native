# ObscuraKit (Kotlin)

Kotlin/JVM kit for the `obscura-pix` Android bridge. Behaviour is defined by
[`docs/KIT_API.md`](../docs/KIT_API.md); this file covers only Kotlin specifics.

## Build and test

```bash
just kotlin-unit                                # ./gradlew :lib:test, no network
just kotlin-integration http://localhost:3000   # ./gradlew :lib:integrationTest
just kotlin-coverage                            # Kover report, lib/build/reports/kover/
just kotlin-publish-local                       # publishToMavenLocal, as the app consumes it
```

- Published as `dev.barrelmaker.obscura.kit:obscura-kit:0.1.0`.
- Kotlin is pinned to the version React Native uses in `obscura-pix`
  (`gradle/libs.versions.toml`). A newer compiler produces a library the app
  cannot read; bump only with a pix RN upgrade.
- `:lib:koverVerify` enforces a unit-suite floor (48% lines, 40% instructions).
  Integration tests are excluded unless `-Pkover.includeIntegration=true`.
- Integration tests default to `https://obscura.barrelmaker.dev`; set
  `OBSCURA_TEST_API` to change it. Each test calls `assumeTrue(checkServer())`,
  so with no server they skip rather than fail. Server setup:
  [`CONTRIBUTING.md`](../CONTRIBUTING.md#integration-tests).

## Facade

```kotlin
val client = ObscuraClient(
    ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev", databasePath = "obscura.db"),
    externalDriver = null,             // or an encrypted AndroidSqliteDriver
    sessionStorage = NoOpSessionStorage,
)

client.send(recipientUserIds, modelKey, entryId, sentAt, payload)   // payload: ByteArray
client.inbox.peek(50); client.inbox.consume(ids); client.inbox.discard(ids, reason); client.inbox.depth()
client.entries.put(model, StoredEntry(id, data, sentAt, authorDeviceId, localMetadata))
client.entries.all(model); client.entries.erase(model, id)
client.uploadAttachment(bytes); client.downloadDecryptedAttachment(id, contentKey, nonce)
client.sendTyping(recipientUserIds, contextId, TypingState.STARTED); client.observeTyping(contextId)
client.processPendingMessages(timeoutMs)
client.friendsChanged.collect { render(client.getFriends()) }
```

- `ObscuraConfig.apiUrl` must be HTTPS; plain HTTP is accepted only for
  `localhost`, `127.0.0.1` and `[::1]`. `databasePath = null` is in-memory.
- An app-supplied `SqlDriver` must create `ObscuraDatabase.Schema` itself; the
  kit creates the schema only for its own JDBC driver.
- `connectionState` and `authState` are `StateFlow`s.
- `incomingMessages` is a wake-up `Channel` with one consumer (the app or a
  test). A full channel drops wake-ups; the inbox still has the data.
- Typing: sends are throttled to one per 2 s per context and state; received
  state expires after 3 s.

## Auth and devices

```kotlin
when (client.login(username, password)) {
    LoginScenario.EXISTING_DEVICE -> client.connect()
    LoginScenario.NEW_DEVICE -> client.loginAndProvision(username, password, deviceName)
    LoginScenario.DEVICE_MISMATCH -> { client.wipeDevice(); client.loginAndProvision(username, password, deviceName) }
    LoginScenario.INVALID_CREDENTIALS -> showError()
}
```

- `register` creates the account, a Signal identity with 100 one-time prekeys,
  and the device, and ends `AUTHENTICATED`.
- **Linking.** The new device (`PENDING_APPROVAL`) connects and shows
  `generateLinkCode()`. The existing device calls
  `validateAndApproveLink(code)`, which sends `DEVICE_LINK_APPROVAL` (challenge
  response, own-device list, friends export) and then announces devices. The
  new device accepts an approval only from its own account while
  `PENDING_APPROVAL`, compares the challenge in constant time (see the known
  gaps in `KIT_API.md`), imports both lists, and becomes `AUTHENTICATED`.
- **Link code:** Base64 JSON `{"d": deviceId, "c": base64(16-byte challenge),
  "t": epochMillis}`. Valid for 5 minutes; rejected if more than 60 s in the
  future.
- **Sessions.** `connect()` refreshes tokens in the background and persists the
  rotated refresh token through `SessionStorage`. `restorePersistedSession()`
  restores, refreshes and connects. `restoreSession(token, refreshToken,
  userId, deviceId, username)` restores without storage.
- `logout()` disconnects, forgets credentials and clears `SessionStorage`;
  local data stays. `fullLogout()` also stops all background jobs and typing
  state. `wipeDevice()` logs out and deletes all local kit data.
- `takeoverDevice()` replaces this device's identity and prekeys on the server
  and drops its sessions with accepted friends' devices.
- The gateway reconnects with backoff 1 s doubling to a 30 s cap. REST calls
  retry HTTP 429/503 twice, honouring `Retry-After` up to 10 s.

## Friend codes

`friendCode()` returns standard Base64 of `{"u": userId, "n": username}`.
`addFriendByCode(code)` strips whitespace and soft hyphens (`U+00AD`, added by
iOS share sheets), accepts the URL-safe alphabet, rejects an empty `u` or `n`,
then calls `befriend`. The code is not secret; identity is pinned by the Signal
session on first contact.

## Code map (`lib/src/main/kotlin/dev/barrelmaker/obscura/kit/`)

| Path | Role |
|---|---|
| `ObscuraClient.kt` | Facade and receive loop (decrypt, route, persist, ack). |
| `network/` | `APIClient` (REST), `GatewayConnection` (WebSocket). |
| `messaging/Messenger.kt` | Signal encrypt/decrypt and session building. |
| `managers/` | Auth, devices, friendships, sends, attachments. |
| `stores/` | SQLDelight-backed inbox, entries, friends, devices. |
| `wire/` | `WireCodec`, payload disposition, typing tracker. |
| `crypto/` | Signal store, attachment AES-GCM, link codes, UUID codec. |

## Pitfalls

- **Messenger confinement.** `Messenger` and each store run on
  `Dispatchers.Default.limitedParallelism(1)`. Keep HTTP off the `Messenger`
  dispatcher: the receive path shares it, so a slow request stalls
  decrypt, persist and ack for everyone.
- **One send path.** Every send goes through `Messenger.queueMessage`, which
  builds a session from the peer's prekey bundle on first contact.
  `Messenger.addressFor` is the only `SignalProtocolAddress` constructor; send
  and receive must build identical addresses or the session splits.
- **JUnit 5 ignores a non-void `@Test`.** A body ending in `assertThrows(...)`
  needs a trailing `Unit`.
- **`runBlocking`, not `runTest`, for real I/O.** `runTest` uses virtual time,
  so `withTimeout` expires before OkHttp's callbacks fire.
- **Fan-out in tests.** `befriend`, `send` and friends go to every device of
  the target. With several devices connected, drain every device's
  `incomingMessages`, or a stale `FRIEND_REQUEST` answers the next
  `waitForMessage()`.
- **Facade-only tests.** Integration tests drive `ObscuraClient`. Raw protobuf
  is allowed only in `AckSemanticsTests` and `FriendGraphIntegrityTests`
  (adversarial input). Check with
  `rg -n "obscura\.v1\.|obscura\.client\.v1\.|ClientMessage\.newBuilder" lib/src/integrationTest`.
- **Generated names.** The SQLDelight column `data` is `data_` in Kotlin.
  `okio.ByteString` (WebSocket frames) and `com.google.protobuf.ByteString` are
  different types.
