# Entry flow

## Sending

```text
obscura-pix resolves the audience and serializes opaque payload bytes
    ↓
ObscuraClient.send(to:modelKey:entryId:sentAt:payload:)
    ↓
fan out to every device of each caller-named user
    + own other devices
    - this sending device
    ↓
Messenger establishes device-UUID Signal sessions and encrypts
    ↓
POST /v1/messages
```

The kit does not write a local outgoing entry. The application already owns the
payload and writes its own copy through `client.entries`.

## Receiving

```text
gateway envelope
    ↓
validate sender_id + sender_device_id
    ↓
decrypt through the sender device UUID's Signal session
    ↓
classify the declared client.proto arm
    ├─ APP_ENTRY / unknown → inbox.put
    ├─ friend/device arms                      → kit-owned handler
    ├─ TYPING_SIGNAL                           → in-memory typing tracker
    └─ declared unimplemented arms             → diagnose and drop
    ↓
emit optional wake-up event
    ↓
acknowledge the server envelope
```

For inboxed content, the durable write completes before the acknowledgement.
The app then performs:

```text
inbox.peek → authorize/merge in obscura-pix → entries.put → inbox.consume
```

Signal sessions are keyed by device UUID, never `registrationId`.
