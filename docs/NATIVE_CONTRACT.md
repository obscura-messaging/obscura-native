# Native client contract

**Contract version: 1**

The prose companion to the repository-local client schema and conformance
vectors. The schema pins message shape; this document and implementation tests
pin native behavior.

Scope: the client-to-client (kit ↔ kit) contract — the E2E payload the server never sees.
Layers:

- **Transport** —
  [`obscura/v1/obscura.proto`](https://github.com/obscura-messaging/obscura-proto/blob/main/obscura/v1/obscura.proto).
  Shared with the server and specified by
  [`TRANSPORT.md`](https://github.com/obscura-messaging/obscura-proto/blob/main/TRANSPORT.md).
- **Content** — [`protocol/obscura/client/v1/client.proto`](../protocol/obscura/client/v1/client.proto).
- **Semantics** — this document. What the content *means* and how kits act on it.

The Kotlin and Swift implementations MUST conform. "MUST" / "MUST NOT" are
normative.

---

## 0. The kit boundary

**This section governs every other section in this document.** Where another
section conflicts with it, this one wins and the other is wrong.

### 0.1 What a kit is

A kit is the **native platform layer for the Obscura app**. It exists because two
things cannot be done in TypeScript on a phone:

1. **libsignal** ships as `libsignal-java` and `libsignal-swift`. There is no
   supported shared core, so the Signal protocol must be implemented twice.
2. **Background push processing cannot depend on a React Native runtime.**
   Native code must restore the session and drain encrypted messages when the OS
   wakes the app. The current iOS background payload does not launch a
   Notification Service Extension.

A kit is **not** a general-purpose framework. It has exactly one consumer — the
app — and no API-stability obligation to anyone else. It MUST NOT be designed,
documented, or marketed as a reusable data layer.

### 0.2 The rule

> **If the kit reads it, it is a field in `client.proto`.
> If it is not in `client.proto`, the kit MUST NOT read it.**

The client schema *is* the boundary. Everything below follows from this line.

This rule is what makes the boundary reviewable: to check whether a kit has
overstepped, read its field accesses. Any read of application data that did not
come from a declared proto field is a violation, no matter how reasonable it
looks locally.

### 0.3 The kit MUST own

- Transport (REST + gateway WebSocket, envelope ack, offline send queue).
- The Signal protocol: sessions, identity, prekeys, encrypt/decrypt.
- Device provisioning, linking, and takeover.
- The friend graph — needed to address a peer's devices and label ephemeral
  sender state locally (§0.5).
- The durable inbox and the opaque entry store. The push path writes to them
  with the app closed, so they cannot live in the app's runtime. A kit owns no
  message model of its own: an inbox row is opaque payload bytes plus the
  transport identity fields, and what a message *is* belongs to the app.
- Attachment encryption, upload, download.
- The push-wake path: decrypt → persist → notify.

### 0.4 The kit MUST NOT

- **MUST NOT parse an application payload.** `AppData.payload` is opaque bytes.
- **MUST NOT know an application model name.** `"pix"`, `"directMessage"`, and
  friends are opaque keys the kit stores and echoes back. A model name MUST NOT
  appear as a literal in kit source.
- **MUST NOT resolve recipients.** The caller names them. A kit fans out to the
  devices of the userIds it is given, and makes no delivery decision of its own.
- **MUST NOT read an application field by name.** No `data["conversationId"]`,
  no `data["senderUsername"]`, no field sniffing of any kind.
- **MUST NOT implement query, relationship, or observation APIs.** Derived state
  is the app's job.
- **MUST NOT accept configuration that names application concepts.** A settings
  field like `conversationModel` is proof the boundary has already been crossed:
  the kit only needs to be told what an app's data *means* if it is doing
  something it should not be doing.
- **MUST NOT post an OS notification.** Notification policy and copy belong to the app.

### 0.5 Sender identity

A notification and a UI label MUST name the sender using the **local friend
graph**, keyed by the server-stamped `sender_id` after successful Signal
decryption through the `sender_device_id` session. A kit MUST NOT take a display
name from a message payload — a payload-supplied name is attacker-controlled
and lets a peer choose how they are labelled on screen.

The Signal session proves possession of the selected device key. The envelope
supplies the server-stamped user label; the payload supplies application
content. §0.10 defines the trust boundary and the currently missing ownership
cross-check.

The one exception — a `FriendRequest` from someone not yet in the graph — is
carved out in §0.10 rule 5, which also states the envelope fields this keying
uses and their trust status.

### 0.6 The app MUST own

Model semantics and validation; recipient resolution; all derived state
(queries, filters, sorting); notification copy; and expiry when implemented.
The current app rules are defined in
[`DOMAIN_CONTRACT.md`](https://github.com/obscura-messaging/obscura-pix/blob/main/docs/DOMAIN_CONTRACT.md).

### 0.7 Consequences

- Adding a **field** to existing content: app only. The kit never sees it.
- Adding a **new notifiable content type**: a deliberate `client.proto` change
  plus both kits. This is rare, and it should be deliberate — you are also
  designing new notification UX at the same time.
- If a kit cannot do its job using declared proto fields alone, **fix the proto**.
  Reaching into the payload is never the answer.

### 0.8 Why this section exists

The boundary is explicit because a generic data engine can look locally useful
inside one kit while duplicating application logic across platforms. Review kit
changes against the shipping application's needs and keep model semantics in
one application-owned implementation.

### 0.9 Receive: persist-then-ack

**An ACK is a DELETE. Ack only what you have durably persisted.**

As defined by the transport contract, a gateway ack is destructive: the server deletes the acked envelope from
the `messages` table (no tombstone, no redelivery). A message is redelivered only
because it is *still on the server* — a fresh `MessagePump` on the next connection
re-reads every remaining row. Therefore the ack is the client's commitment that it
no longer needs the server's copy, and it MUST NOT be sent until the message is in
the kit's own durable store.

Normative rules for the receive loop, identical in both kits:

1. A kit MUST NOT ack an envelope whose decrypt threw.
2. A kit MUST NOT ack an envelope it skipped (e.g. a rate-limited sender). The
   message stays on the server to be retried later.
3. A kit MUST NOT ack until the durable persistence step for that message has
   completed successfully. If persistence throws, the kit MUST NOT ack.
4. The strict order per envelope is **decrypt → persist → (notify) → ack**. Any
   in-process notification (a wake-up channel/flow the app observes) is emitted
   *after* persistence and carries no data that persistence did not already store,
   so it MAY be dropped under backpressure without loss — but only because the
   durable store, not the notification, is the delivery path, and persistence
   happened-before the ack. A notification that is the *sole* delivery path for a
   message that then gets acked MUST NOT be silently droppable.

### 0.10 Envelope identity: who sent this

The shared transport `Envelope` carries **both** identifiers, each stamped by the server
from the sender's device-scoped token and therefore unforgeable by the sender:

| Field | Meaning | Signal's equivalent |
|---|---|---|
| `sender_id` | the sending **user** (16-byte UUID) | `Envelope.source_service_id` |
| `sender_device_id` | the sending **device** (16-byte UUID) | `Envelope.source_device` |

Both are **hints — for routing, session selection and labelling. Neither is a
trust root.** The trust root is the Signal session: a valid MAC proves possession
of that session's chain key, which only the sending device holds.

1. A kit MUST select the inbound Signal session by `sender_device_id`. Signal
   sessions are pairwise device-to-device and a `SignalMessage` carries no sender
   identity, so nothing else on the wire can choose the session. An envelope whose
   `sender_device_id` is absent or not 16 bytes is an **error**: a kit MUST NOT
   guess a device, iterate candidate sessions, or fall back to a default device id.
2. A kit MUST key its local Signal address (`ProtocolAddress`) on the **device
   UUID**. `registrationId` MUST NOT be used as an addressing identifier: it is
   carried on exactly one wire surface (`PreKeyBundleResponse`), while the device
   UUID is carried on all of them. The address is a purely local store key and is
   never transmitted.
3. A kit MUST select a peer's prekey bundle by device UUID, with **no fallback**
   to an arbitrary bundle. Encrypting once under one device's keys and fanning
   that ciphertext to every device means exactly one device can decrypt it.
4. `authorDeviceId` MUST be derived from the **address of the session that
   decrypted** the message — never from a wire field. A malicious server that lies
   about `sender_device_id` can cause a decryption failure, but can never forge an
   attribution.
5. The display name MUST come from the local friend graph keyed on the
   authenticated sender (§0.5). The **friend-request bootstrap** is the single
   exception: a `FriendRequest` arrives from a user who is not yet in the graph, so
   its payload `username` is the only name available. It is attacker-chosen and MUST
   be treated as a request-time label, never as an authenticated identity, and MUST
   NOT be persisted as the friend's name once the friendship is accepted.
6. A kit that already knows which user owns `sender_device_id` (from its friend
   graph or a prekey fetch) SHOULD cross-check `sender_id` against it and log a
   mismatch as a security event. Neither kit does this today; the residual exposure
   is a **mis-labelled** message, never a forged one, because the content is
   authenticated by a session the server does not hold.

---

## 1. Delivery targeting

### 1.1 Explicit entry recipients

The caller names entry recipients. The native layer delivers to exactly that
set, includes the author's other devices for self-sync, and excludes the
sending device. It MUST NOT infer or broaden an application audience.
`KIT_API.md` §5 defines this send path.

### 1.2 Explicit typing recipients

The caller names typing recipients exactly as it names entry recipients.
`TypingSignal.context_id` is opaque correlation data: the native layer MUST NOT
parse it, derive recipients from it, or broaden the supplied audience.

Typing state is explicit and typed:

- `TYPING_STATE_STARTED` refreshes the sender device's short-lived state.
- `TYPING_STATE_STOPPED` clears that sender device's state immediately.
- unspecified or unknown states are ignored.

Typing signals are ephemeral, in-memory, throttled, and allowed to expire
without durable inbox storage.

---

## 2. Incoming ordering metadata

### 2.4 Future-timestamp clamp

An incoming `timestamp` more than **60s** beyond local wall-clock is clamped to
`now + 60s` before it participates in the application's REPLACE ordering, so a
spoofed far-future timestamp cannot win every future conflict forever.

The clamp is normative on the **incoming** path, and both kits apply it there
(`clampFutureTimestamp`, called from the inbox write). On the **local-write** path it is advisory:
`obscura-pix`'s `nextSentAt` returns `max(now, existing.sentAt + 1)` to keep
local writes strictly increasing. It may exceed `now + 60s` immediately after a
peer row was clamped to the ceiling; the receiver clamps it again.

*Not vector-tested:* the clamp is relative to wall-clock `now`, which a static fixture cannot
express deterministically, so it is verified in implementation tests instead.

## 3. Wire (encoding)

*Vectors: [`protocol/conformance/wire.json`](../protocol/conformance/wire.json).*

The client content is a
[`ClientMessage`](../protocol/obscura/client/v1/client.proto). This
section pins two things about it: the **wire ↔ app-facing-form mappings** (the message
kind and typed typing state; `EncryptedMessage.Type` is transport, not content) and **round-trip preservation** of a
`AppEntry`.

### 3.1 Message kind and enum mappings

The message kind is the `ClientMessage.payload` **oneof**: exactly one arm is
set, and *which* arm is set is the message type — there is no separate `Type`
enum to keep in sync (a kind/content mismatch is unrepresentable). The app-facing
type string is the oneof field name upper-snake-cased (`friend_request` →
`"FRIEND_REQUEST"`, `app_entry` → `"APP_ENTRY"`).

| Wire form | App-facing form | Rule |
|---|---|---|
| `ClientMessage.payload` arm e.g. `app_entry` | `"APP_ENTRY"` | oneof field name, upper-snake |
| `TypingState` `TYPING_STATE_STARTED` | `started` | explicit typed state |
| `TypingState` `TYPING_STATE_STOPPED` | `stopped` | explicit typed state |

An unset payload maps to `""` (ignored). `TYPING_STATE_UNSPECIFIED` and any
unrecognized typing state are ignored. These mappings MUST live in one place per
kit (a `WireCodec`), never duplicated, so they cannot drift within a kit.

### 3.2 Round-trip

`encode(AppEntry) → decode` MUST preserve `model`, `id`, `timestamp`, and
the `data` **value**. `data` is model-defined JSON carried in a proto `bytes`
field; equality is by parsed value, so key order is irrelevant.

### 3.3 What is deliberately NOT specified: byte-canonicity

There is intentionally **no canonical byte encoding**. Neither the inner `data`
JSON nor proto3 serialization is guaranteed byte-identical across
languages/libraries, and nothing needs it to be:

- **Signal already authenticates and integrity-protects the whole
  `ClientMessage`.** Sender authenticity and tamper-evidence are provided by the
  encryption layer, over the payload regardless of byte order.
- `data` is **parsed into a map and compared by value**, never by bytes.
- Dedup is by entry `id`; the transport idempotency key is computed by the
  sender over its own outgoing bytes and never needs cross-device reproducibility.

Byte-canonicity would only matter if an app-level signature verification or
content-addressing were introduced. It is not, so pinning exact bytes would
constrain the wire for a property nothing consumes. **If such a feature is ever
added, a canonical `data` encoding (e.g. sorted-key JSON) must be defined
first.**

---


## History

See [`HISTORY.md`](HISTORY.md) and Git history for superseded behavior and
migration records.
