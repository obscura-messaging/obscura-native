# Authentication & Device Linking

## First Device

```kotlin
val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"))
client.register("alice", "mypassword123!")  // 12+ character password
client.connect()
```

`register()` creates the user account on the server, generates a Signal Protocol identity (keypair + 100 one-time prekeys), provisions the device, and authenticates. After this call, `authState` is `AUTHENTICATED` and you can start sending entries, draining the inbox, and befriending other users.

`login()` returns a `LoginScenario`. Only `EXISTING_DEVICE` authenticates; the
other outcomes leave the client logged out (see `docs/KIT_API.md`):

```kotlin
when (client.login("alice", "mypassword123!")) {
    LoginScenario.EXISTING_DEVICE -> client.connect()
    LoginScenario.NEW_DEVICE -> client.loginAndProvision("alice", "mypassword123!", "Alice's Phone")
    LoginScenario.DEVICE_MISMATCH -> { client.wipeDevice(); client.loginAndProvision("alice", "mypassword123!", "Alice's Phone") }
    LoginScenario.INVALID_CREDENTIALS, LoginScenario.USER_NOT_FOUND -> showError()
}
```

## Adding a New Device

When the account already has a device, a new device **must be approved** by it: `loginAndProvision()` puts the new device in `PENDING_APPROVAL` until approval arrives. If no other device exists, nothing could approve it, so it becomes `AUTHENTICATED` directly.

### New device side

```kotlin
val newDevice = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"))
newDevice.loginAndProvision("alice", "mypassword123!", "Alice's Tablet")
// authState is now PENDING_APPROVAL — not AUTHENTICATED

newDevice.connect()
val linkCode = newDevice.generateLinkCode()

// Display linkCode as a QR code or copyable text.
// Wait for authState to change to AUTHENTICATED.
```

### Existing device side

```kotlin
// User scans QR or pastes the code from the new device
existingDevice.validateAndApproveLink(linkCode)
```

`validateAndApproveLink()` checks that the link code is valid (not expired, correct format, 16-byte challenge), then sends a `DEVICE_LINK_APPROVAL` message to the new device containing:

- The challenge response (proving the approver saw the code)
- The account's device list
- Friend data export

### What happens on approval

When the new device receives `DEVICE_LINK_APPROVAL`:

1. Challenge is verified (constant-time comparison)
2. Device list is imported
3. Friend data is imported
4. Auth state transitions to `AUTHENTICATED`

After this, the new device can use the full API — send, inbox, entries, everything.

Friend data reaches the new device through `friendsExport` on the approval
message itself. There is no separate state-transfer message.

### Auth states

| State | Meaning |
|-------|---------|
| `LOGGED_OUT` | No session. Call `register()` or `login()`. |
| `PENDING_APPROVAL` | Device provisioned but not yet approved. Call `generateLinkCode()` and wait. |
| `AUTHENTICATED` | Full access. First device gets this immediately. Second+ devices get this after approval. |

## Link Code Format

The link code is a Base64-encoded JSON object:

```json
{
  "d": "device-id",
  "u": "device-uuid",
  "k": "base64(signal-identity-public-key)",
  "c": "base64(16-byte-random-challenge)",
  "t": 1711234567890
}
```

- Short keys keep the QR code small
- 5-minute expiry (configurable)
- Challenge is random per-generation (prevents replay)
- Challenge verification is constant-time (prevents timing attacks)

## Connection

```kotlin
client.connect()      // WebSocket + decrypt/route/ACK loop + token refresh
client.disconnect()
```

`connect()` opens a WebSocket to the server, starts the envelope decryption loop, and begins automatic token refresh. Reconnection uses exponential backoff (1s → 2s → 4s → 8s → 16s → 30s max).

The developer doesn't manage the connection beyond calling `connect()` once. Offline messages are queued by the server and delivered on reconnect.

## Session Restore (App Restart)

For file-backed databases (production), restore the session instead of re-registering:

```kotlin
val client = ObscuraClient(ObscuraConfig(apiUrl = "...", databasePath = "obscura.db"))
client.restoreSession(
    token = savedToken,
    refreshToken = savedRefreshToken,
    userId = savedUserId,
    deviceId = savedDeviceId,
    username = savedUsername,
    registrationId = savedRegId
)
client.connect()
```

Signal keys, friend lists, inbox rows and stored entries persist in the database. The session restore just re-establishes the network identity.

## What You Can Rely On

- **Password is the only required credential.** 12+ characters, that's it.
- **Device linking is enforced.** While another device exists, `loginAndProvision()` → `PENDING_APPROVAL` → approval required.
- **Link codes expire.** 5 minutes by default. Each code has a unique random challenge.
- **Challenge verification is constant-time.** No timing side-channel.
- **Token refresh is automatic.** The library handles JWT expiry transparently.
- **Reconnection is automatic.** Exponential backoff, no developer intervention.
- **HTTP 429/503 retries automatically.** Rate limiting and transient failures retry twice with backoff.
