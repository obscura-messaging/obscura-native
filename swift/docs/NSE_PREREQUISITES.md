# Notification Service Extension prerequisites

No NSE exists, and iOS push is not active. The server sends a content-free
background notification (`content-available: 1`), which cannot launch an NSE.
`obscura-pix` does not yet forward the APNs token or handle the background
wake.

## Already in place (unverified on device)

- `obscura-pix` `SharedContainer` prefers the App Group container for the
  SQLCipher database and falls back, with a log line, to private storage.
- `ObscuraClient(apiURL:dataDirectory:userId:keychainAccessGroup:)` stores the
  database key in a shared keychain group.
- `obscura-pix` `KeychainSession` stores the access and refresh tokens, user
  ID, device ID and username in that group.

## To enable push with the app's background handler

1. Enable Push Notifications and Background Modes → Remote notifications.
2. Forward the APNs token through `pushTokenReceived` to `registerPushToken`.
3. On the wake, call `processPendingMessages` and post generic local copy.

## To add an NSE

1. Replace the background payload with a privacy-reviewed NSE-compatible one
   (server change).
2. Add the NSE target with the same App Group entitlement and provisioning.
3. Migrate existing private-container databases and keychain items, or require
   a wipe when shared storage first becomes available.
4. Support concurrent database access from two processes (WAL, pooling).
5. Ensure only one process drains the gateway at a time.
6. In the NSE: restore the shared session, call `processPendingMessages`, set
   generic copy on the incoming notification, and complete it through the
   content handler. Do not post a second notification.

Before calling it supported, verify on physical hardware: App Group
provisioning, keychain access while locked, token refresh, database
concurrency and delivery.
