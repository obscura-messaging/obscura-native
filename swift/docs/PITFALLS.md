# Pitfalls — Things That Will Waste Your Time

## Build

**Requires macOS 13+ and Xcode 16+.** The project uses `URLSessionWebSocketTask` (macOS 13+), CryptoKit, and Swift 6.1 toolchain. Ensure Xcode is up to date — the Swift toolchain uses Xcode's SDK for platform headers.

**`LIBRARY_PATH` must point to libsignal FFI.** The vendored libsignal Rust FFI (`.a` file) must be on the library path. Use `LIBRARY_PATH="$(pwd)/vendored/libsignal/target/release" swift build`. The `dev.sh` helper sets this automatically.

## libsignal

**Use v0.40.0, NOT latest.** The latest (v0.90+) requires Kyber (post-quantum) keys for ALL PreKeyBundle constructors. The server doesn't support Kyber. v0.40.0 has non-Kyber constructors that work with the current server.

**The Rust FFI must be built with the `build_ffi.sh` script**, not just `cargo build`. The script includes testing symbols (`signal_testing_*`) that the Swift wrapper references. Without them, linking fails.

**`InMemorySignalProtocolStore` is fine for tests but not production.** Sessions are lost on process exit. Use `PersistentSignalStore` (GRDB-backed) which implements all 6 libsignal protocol interfaces with SQLite persistence.

**`ProtocolAddress` is `(deviceUUID, 1)`.** Session addressing rules are in
[`docs/KIT_API.md`](../../docs/KIT_API.md).
`TwoDeviceSendTests` covers live two-device fan-out across a gateway reconnect;
it does not reconstruct a client or prove cold-start session persistence.

## WebSocket

**The project uses `URLSessionWebSocketTask` from Foundation.**

**Linux is not a supported package build target.** GRDB's bundled SQLCipher
requires Apple's `CommonCrypto`, and Signal persistence depends on GRDB. Full
build and scenario validation therefore require macOS.

**The envelope loop must use a buffered queue for `waitForMessage()`.** If you create a fresh `AsyncStream` subscription after the message has already been processed by the loop, you miss it. The `messageQueue` array in ObscuraClient buffers processed messages for test consumption.

**Gateway timeout should be ≤30 seconds in the envelope loop.** Cancellation
cannot complete while a task remains blocked in the receive timeout.

## Server

**Server rate limits are per-instance (3 instances behind load balancer):**
- General endpoints: 10 req/s sustained, 20 req/s burst
- Auth endpoints (register, login, refresh): 1 req/s sustained, 3 req/s burst

Use `await rateLimitDelay()` (100ms) between general calls and `await authRateLimitDelay()` (1000ms) between auth calls. Both delays are configurable via `SERVER_REQUEST_DELAY_MS` and `AUTH_REQUEST_DELAY_MS` in Constants.swift. Tests must run serially, not in parallel.

**Password must be ≥12 characters.** The server rejects shorter passwords with HTTP 400.

**Device provisioning validates XEdDSA signatures.** You cannot use dummy/fake keys for provisioning. The `identityKey` must be a real Curve25519 key (33 bytes, 0x05 prefix) and the `signedPreKey.signature` must be a valid XEdDSA signature (64 bytes) signed by the identity key. libsignal handles this correctly.

**`POST /v1/messages` requires `Idempotency-Key` header** (content-hash based) and `Content-Type: application/x-protobuf`. Missing either causes 400.

## Protobuf

**Generated proto types are `internal`, not `public`.** SwiftProtobuf defaults to
internal visibility. Public APIs use serialized `Data` and deserialize
internally.

## Testing

**All scenario tests hit the real server at `obscura.barrelmaker.dev`.** They create real users, real Signal sessions, real WebSocket connections. Each test registers new unique usernames (`test_RANDOM`).

**Tests that call `client.connect()` start an envelope loop task.** This task must be cancelled via `client.disconnect()` before the test ends, otherwise it blocks the next test. The `ObscuraClient.deinit` handles this, but only if the client is deallocated (not retained by test references).

**Use `constantTimeEqual` for identity key comparison.** Do not compare secret
material with `Data ==`.
