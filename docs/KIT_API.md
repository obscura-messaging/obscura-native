# Thin Kit API Contract

Status: **normative** for `ObscuraKit-Kotlin`, `ObscuraKit-swift`, and their
application bridges.

This document defines the boundary between an Obscura kit and an application.
`NATIVE_CONTRACT.md` defines the shared behavioral rules; `client.proto` defines every
payload field a kit may inspect. Historical migrations and removed APIs belong
in `HISTORY.md`, not here.

---

## 1. Shape

A kit owns:

- identity, device linking, friendship state, and Signal sessions;
- transport, authentication, encryption, and attachment ciphertext transfer;
- durable receipt of authenticated payload bytes;
- a small opaque entry store used by the application bridge;
- delivery of explicitly addressed app payloads.

The application owns:

- model schemas and payload parsing;
- audience resolution;
- merge and conflict resolution;
- expiry, queries, filters, and sorting;
- notification policy and copy.

If a kit needs to inspect a field, that field MUST be declared in
`client.proto`. A kit MUST NOT infer application semantics from JSON or model
names.

The durable receive path is:

```text
gateway envelope
  -> decrypt and authenticate
  -> classify the declared proto arm
  -> persist bytes or complete kit-internal handling
  -> emit an optional wake-up event
  -> acknowledge the envelope
```

The wake-up event is not the delivery path. The inbox is.

---

## 2. Receive durability

An acknowledgement deletes the server's copy. Both kits therefore obey these
rules:

1. Do not acknowledge a payload that failed decryption.
2. Do not acknowledge a payload skipped because processing was deferred.
3. For an inboxed payload, complete the durable write before acknowledging it.
4. For kit-internal payloads, complete the handler before acknowledging them.
5. A duplicate envelope already present in the inbox is successfully persisted;
   acknowledge it normally.
6. Emit in-process notifications only after the durable step. They may be
   dropped or coalesced because they carry no unique data.
7. Unknown payload arms are durable opaque data, not permission to guess at a
   schema.

The app MUST make its merge idempotent. Inbox uniqueness suppresses redelivery
only while a row remains pending; consumed envelope IDs are not retained as
tombstones.

---

## 3. Durable inbox

### 3.1 Record

Each inbox row exposes:

| Field | Type | Meaning |
|---|---|---|
| `id` | integer | Local row identifier used by `consume` and `discard`. |
| `kind` | string | Declared proto payload arm, or an unknown/unset marker. |
| `senderUserId` | string | Server-stamped `Envelope.sender_id`; never read from app payload data. |
| `senderDeviceId` | nullable string | Device UUID whose Signal session decrypted the message. |
| `modelKey` | nullable string | Declared `AppEntry.model`; null for other arms. |
| `entryId` | nullable string | Declared `AppEntry.id`; null for other arms. |
| `sentAt` | nullable integer | Declared timestamp, clamped per `NATIVE_CONTRACT.md` §2.4. |
| `payload` | bytes | Opaque serialized payload bytes. |

Successful decryption authenticates the session selected by `senderDeviceId`.
`senderUserId` is a server-stamped routing hint and SHOULD be cross-checked
against the local owner of that device when known (`NATIVE_CONTRACT.md` §0.10). Neither
identity field comes from application payload data. Model fields are routing
metadata, not authorization.

### 3.2 API

The public inbox has exactly four operations:

```text
peek(limit = 50) -> [InboxRecord]
consume(ids)     -> void
discard(ids, reason) -> void
depth()          -> integer
```

- `peek` returns the oldest pending rows and has no side effects.
- `consume` deletes rows the app durably processed.
- `discard` deletes rows the app deliberately refuses to process and records the
  supplied reason in the kit's security log.
- `depth` reports the number of pending rows.

There is no public insert, cursor, retry counter, or mutable error field.

### 3.3 Rules

1. Persist before acknowledgement.
2. Delete only through successful `consume`, explicit `discard`, or a
   security-required whole-store wipe.
3. `peek` is stable and ordered oldest first.
4. `consume` is idempotent and accepts partial batches.
5. `discard` is explicit, reasoned, and observable.
6. A wake-up event may be dropped only after persistence.
7. The application must monitor `depth`; unbounded growth is not a recovery
   strategy.
8. The receive path keeps the transport envelope ID internally and unique while
   a row is pending; it is not part of the public record.
9. Only the receive path writes the inbox.

### 3.4 Unprocessable rows

There is no skip cursor. A bad oldest row must not be hidden behind a cursor
that eventually makes it unreachable. The app either:

- leaves the row pending for a known transient condition;
- durably processes it and calls `consume`; or
- concludes it can never be processed and calls `discard` with a reason.

This keeps permanent data loss explicit and prevents poison rows from being
silently bypassed.

### 3.5 Backlog pressure

The inbox has no automatic eviction policy. If the app stops draining it, disk
pressure can eventually make persistence fail. The kit must then refuse to
acknowledge new envelopes, leaving them on the server. Applications must surface
abnormal inbox depth before that chain reaches the server's queue limit.

---

## 4. Payload classes

Classification describes current receive behavior; it does not assign
application meaning.

| Class | Receive behavior |
|---|---|
| `inboxed` | Persist opaque bytes, then acknowledge. |
| `kitInternal` | Complete the kit's identity/session handler, then acknowledge. |
| `droppable` | Best-effort ephemeral data; it may be acknowledged without durable storage. |
| `unimplemented` | Record a diagnostic and acknowledge so one unsupported arm cannot wedge the queue. |

### 4.1 Unknown payloads and authorization

An unknown or unset payload arm is `inboxed` so a newer sender does not cause an
older receiver to destroy data it cannot interpret.

Successful delivery is not application authorization. Any authenticated user
may be able to address a device, so the application MUST authorize inboxed
content using the server-stamped user identity and session-authenticated device
attribution before applying it. Payload fields never override either source.

### 4.2 Current classification

| Payload arm | Kotlin | Swift | Notes |
|---|---|---|---|
| `APP_ENTRY` | inboxed | inboxed | Primary app payload. |
| `FRIEND_REQUEST` | kit-internal | kit-internal | Friendship bootstrap. |
| `FRIEND_ACCEPT` | kit-internal | kit-internal | Friendship bootstrap. |
| `DEVICE_ANNOUNCE` | kit-internal | kit-internal | Linked-device state. |
| `DEVICE_LINK_APPROVAL` | kit-internal | unimplemented | Swift has no receive handler. |
| `TYPING_SIGNAL` | droppable | droppable | Explicit, typed ephemeral typing state. |
| unknown/unset | inboxed | inboxed | Preserved as opaque bytes. |

---

## 5. Send and attachments

### 5.1 App payload send

The canonical send operation accepts an explicit audience:

```text
send(
  recipientUserIds,
  modelKey,
  entryId,
  sentAt,
  payloadBytes
)
```

The caller resolves the application audience and supplies opaque payload bytes.
The kit:

1. resolves every addressed user's current devices;
2. includes the sender's other devices for self-sync;
3. excludes the sending device;
4. encrypts independently for each recipient device;
5. uploads one envelope per encrypted device payload.

Payload-size and application-schema validation are caller responsibilities.

The kit MUST NOT broaden an unresolved audience. For a recipient with no usable
device keys, it skips or reports that recipient according to the platform API;
it never substitutes another recipient.

Partial-recipient delivery is currently best effort and is not exposed with
enough detail for the app to present per-recipient failure. Callers must not
interpret a successful method return as proof that every device received the
message.

This explicit-audience send is the only app payload send either kit exposes.
Attachment upload and download are byte primitives; their metadata travels
inside the application's explicit-audience model payload.

### 5.2 Attachments

Both kits expose `uploadAttachment(plaintext)`, which encrypts inside the kit
(AES-256-GCM, fresh key and nonce per attachment) and returns the attachment
identifier with its `contentKey` and `nonce`. The caller never handles
unencrypted upload bytes. The server stores ciphertext and issues the opaque
identifier. The application decides
which attachment metadata to include in its encrypted payload. The current app
carries the identifier, key material, nonce, and an app-level media kind; MIME
type and size are not part of the shared kit contract.

Downloads return ciphertext to the kit, which decrypts locally and may keep the
decrypted bytes in its database cache. `purgeAttachment(id)` removes this
device's decrypted copy unrecoverably (under `secure_delete`, then a WAL
truncate); the server's ciphertext is untouched. The app calls it when the
entry referencing the attachment expires. Attachment
metadata is application data; the kit does not infer model semantics from it.

---

## 6. Push drain and app events

Both kits expose push-token registration and a bounded
`processPendingMessages` operation.

`processPendingMessages(timeout)`:

- connects or reuses the receive path;
- waits for receive activity within the supplied budget;
- returns one opaque integer: the number of successfully processed envelopes;
- counts harmless redeliveries as processed;
- does not consume the app's event stream or inbox;
- does not classify results by application model.

Both current implementations return `0` when they cannot connect after their
bounded retries. That is indistinguishable from a successful drain that
processed no envelopes. Callers MUST NOT treat zero as proof that the server
queue is empty; connection failure remains observable through kit logging and
connection state.

The return value is telemetry, not notification content. The application drains
the inbox and decides whether any user-visible notification is appropriate.

Overlapping drains may be serialized or coalesced. They must not duplicate app
events for one inbox insertion.

Aggregate friendship events are payload-free `friendsChanged` wake-ups. Hosts
call `getFriends` after receiving one; the canonical store observation remains
inside the kit and full friend arrays are not copied into events.

Debug output is also pull-only. Hosts call `getDebugLog`; debug lines are never
emitted as live aggregate events.

---

## 7. Application obligations

An application drain performs this sequence:

1. `peek` a bounded batch.
2. Validate the row shape and decode the app payload.
3. Authorize and plan the merge using the transport identity fields.
4. Write each accepted merged entry through the opaque entry store.
5. Call `consume` only after all corresponding writes complete.
6. Call `discard` only for permanent rejection, with a specific reason.
7. Repeat until the batch is not full, then check `depth`.

The current bridge does not expose a transaction spanning entry writes and
inbox deletion. A crash after step 4 replays the row, so app merge must be
idempotent. Expiry is not implemented.

Notification policy runs after the app has interpreted and committed the data.
The kit never turns model names or drain counts into notification copy.

---

## 8. Entry storage and merge

### 8.1 Opaque entry store

The native entry store is intentionally small:

```text
put(model, entry)
all(model)
erase(model, id)
```

`StoredEntry` contains the application-selected identifier, timestamp,
session-attributed author device, opaque payload bytes/JSON, and nullable
`localMetadata`.

`erase` removes one entry so its contents are unrecoverable from the database
files: the delete runs under `secure_delete`, then the WAL is checkpointed and
truncated. Erasing a missing entry is a no-op. Both kits enable `secure_delete`
on every database, including an app-supplied driver.

`localMetadata` is an opaque, app-owned sidecar for local bookkeeping. `put`
persists it verbatim. It is local-only: the kit never reads its contents,
serializes it into `AppEntry`, or sends it to another device.

The store does not:

- parse model schemas;
- resolve audiences;
- execute filters or sorting expressions;
- merge competing writes;
- enforce expiry (it provides `erase`; the app decides when to call it);
- synchronize a local deletion to peers.

### 8.2 Merge

The app selects a merge rule from its local model configuration when applying
each `APP_ENTRY`. The normative rules live in
[`DOMAIN_CONTRACT.md`](https://github.com/obscura-messaging/obscura-pix/blob/main/docs/DOMAIN_CONTRACT.md):

- `APPEND`: first write for an entry ID wins; later repeats are idempotent.
- `REPLACE`: highest `(sentAt, authorDeviceId)` wins.

`authorDeviceId` comes from the authenticated sender device, never from payload
data. Incoming timestamps are clamped per `NATIVE_CONTRACT.md` §2.4.

### 8.3 Expiry

Expiry is an application concern: the app decides when an entry expires and
calls `erase`, which guarantees the content is unrecoverable locally. The
current app does not expire stored stories or entries automatically. New work must not assume that declaring a TTL
in payload data causes either kit to enforce it.

---

## 9. Deliberately absent

The following do not belong in a kit:

| Surface | Owner |
|---|---|
| Model/schema registry | Application |
| JSON schema parser | Application |
| Query/filter/sort DSL | Application |
| CRDT or generic merge engine | Application |
| Audience/routing engine | Application |
| Notification copy or category policy | Application |
| Model-name classification | Application |
| Story/message expiry policy | Application |
| Public inbox insertion | Receive loop only |
| Automatic inbox eviction | No owner; surface pressure instead |

Do not add one of these to solve an application feature. Pass explicit data
through the boundary or add a declared proto field when the kit genuinely needs
it.

---

## 11. Protocol status and live gaps

These constraints affect compatibility work:

- Swift cannot receive `DEVICE_LINK_APPROVAL`.
- Linked devices do not automatically learn friendships created after linking.
- Device announcements have no replay protection.
- Partial-recipient send failures are not visible to the application.
- Consumed inbox envelope IDs have no durable deduplication tombstone.

Treat this list as current constraints, not a roadmap. Track proposed work in
issues; record completed migrations in `HISTORY.md`.
