# Client API — Auth, Connection, Friends, Devices

Auth, social graph, device management, and opaque entry transport. Entries
reach the app through `client.inbox`, are stored via `client.entries`, and are
sent with `client.send(to:modelKey:entryId:sentAt:payload:)`.

## Client Initialization

```swift
// In-memory (tests) — all state lost on dealloc
let client = try ObscuraClient(apiURL: "https://obscura.barrelmaker.dev")

// File-backed (production) — persists Signal keys, friends, inbox, and entries across restarts
let client = try ObscuraClient(
    apiURL: "https://obscura.barrelmaker.dev",
    dataDirectory: "/path/to/app/data",
    userId: userId  // enables SQLCipher encryption per-user
)
```

File-backed clients restore Signal identity from the database on init. After `restoreSession()`, encryption works immediately without re-registering.

## Auth

```swift
// Register new user — creates account + device + Signal keys
try await client.register(username, password)

// Login existing user
try await client.login(username, password)

// Login with specific device (device-scoped token for messaging)
try await client.login(username, password, deviceId: savedDeviceId)

// Lightweight account-only calls (no Signal keys, no device)
let (token, refreshToken, userId) = try await ObscuraClient.registerAccount(username, password)
let (token, refreshToken, userId) = try await ObscuraClient.loginAccount(username, password)

// Restore session without re-authenticating (file-backed client)
await client.restoreSession(
    token: savedToken, refreshToken: savedRefresh,
    userId: savedUserId, deviceId: savedDeviceId,
    username: savedUsername
)

// Check state
client.hasSession      // true if token + userId set
client.authState       // .loggedOut or .authenticated
client.connectionState // .disconnected, .connecting, .connected, .reconnecting

// Logout
try await client.logout()
```

## Connection

```swift
// Connect WebSocket + start envelope loop + start token refresh
try await client.connect()

// Lifecycle-safe foreground entrypoint; no-ops unless authenticated and fully disconnected
try await client.ensureConnected()

// Disconnect (cancels envelope loop + token refresh)
client.disconnect()

// Token refresh happens automatically. Force-check:
await client.ensureFreshToken()
```

`connect()` starts two background tasks:
1. **Envelope loop** — receives encrypted messages, decrypts, routes to handlers
2. **Token refresh** — proactively refreshes JWT before expiry

Both are cancelled by `disconnect()` or `deinit`.

## Friends

Friends are the social graph. The kit uses them to address devices and to resolve a sender's display name (`NATIVE_CONTRACT.md` §0.5) — it does **not** use them to decide an audience. The caller names recipients (§0.4).

```swift
// Send friend request (encrypted FRIEND_REQUEST)
try await client.befriend(userId, username: "alice")

// Accept friend request (encrypted FRIEND_ACCEPT)
try await client.acceptFriend(userId, username: "alice")

// Bridge-facing aggregate query
let all = await client.getFriends()

// Store-level queries remain available for native callers.
let friend = await client.friends.getFriend(userId)
let accepted = await client.friends.getAccepted()
let pending = await client.friends.getPending()
let isFriend = await client.friends.isFriend(userId)

// Aggregate observation is a payload-free wake-up. Pull current rows after it fires.
for await event in client.observeEvents() {
    if case .friendsChanged = event {
        render(await client.getFriends())
    }
}

// Debug output is pull-only and never appears in ObscuraEvent.
let debugLines = client.getDebugLog()
```

## Devices

Devices define where your data lives. Each device has its own Signal identity.

```swift
// Announce the current device list to all friends.
try await client.announceDevices()

// Query own devices
let devices = await client.devices.getOwnDevices()
let identity = await client.devices.getIdentity()
let hasIdentity = await client.devices.hasIdentity()

// Observe
for await devices in client.devices.observeOwnDevices().values { ... }
```

## Device Linking

New devices must be approved by an existing device. No bypass.

```swift
// NEW DEVICE: generate link code (display as QR or copyable text)
let linkCode = client.generateLinkCode()

// EXISTING DEVICE: scan/paste and approve
try await existingClient.validateAndApproveLink(linkCode)
// This sends: DEVICE_LINK_APPROVAL → DEVICE_ANNOUNCE
```

Link codes expire after 5 minutes. They contain a random challenge, the device's Signal identity key, and a timestamp — all Base58-encoded.

For the full device linking ceremony:
1. New device logs in with `loginAndProvision(username, password)`
2. New device calls `generateLinkCode()` — displays QR
3. Existing device scans, calls `validateAndApproveLink(code)`
4. Existing device sends `DEVICE_LINK_APPROVAL`, but Swift currently drops that
   arm on receive
5. Existing device broadcasts DEVICE_ANNOUNCE to all friends

Because Swift does not handle `DEVICE_LINK_APPROVAL`, the new device does not
import its friend export or complete own-device list. Linking remains partial
until that receive path is implemented.

## Sending Entries

```swift
// An application entry — the app names the recipients and supplies opaque bytes.
try await client.send(
    to: [friendUserId], modelKey: "directMessage", entryId: entryId, payload: jsonBytes)
```

## Receiving Entries

The envelope loop in `connect()` handles all incoming messages automatically:
- FRIEND_REQUEST → stored in `FriendStore`
- FRIEND_ACCEPT → updates friend status
- APP_ENTRY → written to `client.inbox` as a durable row, then acked (an ack is a DELETE, so the write comes first)
- DEVICE_ANNOUNCE → updates friend's device list

The application drains `client.inbox`, authorizes and merges the opaque payload,
writes the result through `client.entries`, then calls `consume`.

For wake-up handling, subscribe to the events stream:

```swift
for await event in client.events() {
    switch event.type {
    case "APP_ENTRY": print("entry from \(event.sourceUserId)")
    default: break
    }
}
```

Or wait for a specific message (tests):

```swift
let msg = try await client.waitForMessage(timeout: 10)
```

## Session Reset

```swift
// Reset Signal session with a specific friend (re-establishes on next message)
try await client.resetSessionWith(friendUserId, reason: "user requested")

// Reset all sessions
try await client.resetAllSessions(reason: "key rotation")
```

## Attachments

```swift
// Encrypts inside the kit; the server only sees ciphertext.
let ref = try await client.uploadAttachment(plaintext)   // (id, contentKey, nonce)
let bytes = try await client.downloadDecryptedAttachment(
    id: ref.id, contentKey: ref.contentKey, nonce: ref.nonce)
```

Embed `id`, `contentKey`, and `nonce` in your encrypted entry payload so the
recipient can download and decrypt. The kit keeps no decrypted copy; each call
fetches and decrypts again, so cache the result in the app if needed.

## Logging

```swift
// Set a custom logger for security-sensitive events
client.logger = MyCustomLogger()

// Default is PrintLogger which logs to stdout
// Events logged: decrypt failures, identity changes, token refresh failures, frame parse errors
```

Implement the `ObscuraLogger` protocol for custom logging.

## Observable State Properties

| Property | Type | Description |
|----------|------|-------------|
| `connectionState` | `ConnectionState` | `.disconnected`, `.connecting`, `.connected`, `.reconnecting` |
| `authState` | `AuthState` | `.loggedOut`, `.authenticated` |
| `hasSession` | `Bool` | `true` if token + userId are set |
| `userId` | `String?` | Current user ID (from JWT) |
| `username` | `String?` | Current username |
| `deviceId` | `String?` | Current device ID (from device-scoped JWT) |
| `token` | `String?` | Current auth token |

## Rate Limiting

The server rate-limits aggressively. Test helpers use the shared pacing functions. If you call
`APIClient` directly, add the matching delay:

```swift
await rateLimitDelay()      // 100ms for general endpoints
await authRateLimitDelay()  // 1000ms for auth endpoints
```
