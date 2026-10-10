# Kit contract

Normative for the Kotlin and Swift kits and their application bridges.
"MUST" / "MUST NOT" are binding.

| Layer | Defined by |
|---|---|
| Transport (shared with the server) | [`obscura.proto`](https://github.com/obscura-messaging/obscura-proto/blob/main/obscura/v1/obscura.proto), [`TRANSPORT.md`](https://github.com/obscura-messaging/obscura-proto/blob/main/TRANSPORT.md) |
| Client content (end-to-end) | [`client.proto`](../protocol/obscura/client/v1/client.proto) |
| Kit behaviour and app-facing API | this document |
| App model rules | [`DOMAIN_CONTRACT.md`](https://github.com/obscura-messaging/obscura-pix/blob/main/docs/DOMAIN_CONTRACT.md) |

## Kit boundary

A kit is the native layer of the Obscura app. It exists because libsignal ships
separately for Java and Swift, and because background push processing cannot
depend on a React Native runtime. Its one consumer is the app; it is not a
general-purpose data layer.

> **If the kit reads it, it is a field in `client.proto`. If it is not in
> `client.proto`, the kit MUST NOT read it.**

**The kit owns** transport (REST, gateway WebSocket, ack, offline send queue);
the Signal protocol; device provisioning, linking and takeover; the friend graph
(to address devices and label senders); the durable inbox and opaque entry store,
which the push path writes with the app closed; attachment encryption and
transfer; and the push-wake path.

**The app owns** model schemas and payload parsing, validation, audience
resolution, merge, expiry, queries/filters/sorting, and notification policy and
copy.

**The kit MUST NOT:**

- parse an application payload (`AppData.payload` is opaque bytes) or read an
  application field by name;
- contain an application model name as a literal (model keys are opaque values
  it stores and echoes back);
- resolve or broaden recipients;
- implement queries, observation, a schema registry, a merge engine, or expiry;
- accept configuration that names application concepts (e.g. `conversationModel`);
- post an OS notification.

Adding a field to existing content is an app-only change. A new notifiable
content type is a `client.proto` change plus both kits. If a kit cannot do its
job from declared proto fields, change the proto.

## Receive: persist-then-ack

An ack deletes the server's copy; nothing is redelivered after it. Per envelope:
**decrypt → classify → persist (or run the kit-internal handler) → optional
wake-up event → ack**.

1. MUST NOT ack an envelope whose decrypt threw.
2. MUST NOT ack a skipped or deferred envelope (e.g. rate-limited sender).
3. MUST NOT ack until the durable write or kit-internal handler succeeded.
4. A duplicate already in the inbox counts as persisted; ack it.
5. Wake-up events follow persistence and carry no data the store lacks, so they
   MAY be dropped or coalesced. The inbox is the delivery path.

## Envelope identity

The server stamps `sender_id` (user UUID) and `sender_device_id` (device UUID)
from the sender's device-scoped token. Both are hints for routing, session
selection and labelling. The trust root is the Signal session.

1. Select the inbound session by `sender_device_id`. If it is absent or not 16
   bytes, fail; never guess, iterate sessions, or fall back to a default device.
2. Key the local `ProtocolAddress` on the device UUID, never `registrationId`.
3. Select a peer's prekey bundle by device UUID, with no fallback bundle.
4. Derive `authorDeviceId` from the session that decrypted the message, never
   from a wire field.
5. A kit that knows the owner of `sender_device_id` SHOULD cross-check
   `sender_id` and log a mismatch as a security event.

**Sender names** come from the local friend graph keyed by `sender_id`, never
from a payload. The one exception is a `FriendRequest` from someone not yet in
the graph: its payload `username` is a request-time label only and MUST NOT be
stored as the friend's name once accepted.

## Future-timestamp clamp

An incoming timestamp more than 60 s past local wall-clock is clamped to
`now + 60s` before it is stored (`clampFutureTimestamp`, called from the inbox
write), so it cannot win every REPLACE conflict. Implementation tests cover it,
not vectors. Local writes may exceed the ceiling; receivers clamp them again.

## Wire encoding

Content is a `ClientMessage`. Vectors in
[`protocol/conformance/wire.json`](../protocol/conformance/wire.json) run in
both platform suites. Each kit keeps the mappings in one `WireCodec`.

| Wire | App-facing |
|---|---|
| set `ClientMessage.payload` arm, e.g. `app_entry` | upper-snake name, `"APP_ENTRY"` |
| unset payload | `""` (ignored) |
| `TYPING_STATE_STARTED` / `_STOPPED` | `started` / `stopped` |
| `TYPING_STATE_UNSPECIFIED`, unrecognised | ignored |

`encode(AppEntry) → decode` MUST preserve `model`, `id`, `timestamp` and the
`data` value (JSON in a `bytes` field, compared by parsed value). Byte-canonical
encoding is not required; define one before adding anything that signs or
content-addresses payloads.

## Payload classes

| Arm | Kotlin | Swift |
|---|---|---|
| `APP_ENTRY` | inboxed | inboxed |
| `FRIEND_REQUEST`, `FRIEND_ACCEPT`, `DEVICE_ANNOUNCE` | kitInternal | kitInternal |
| `DEVICE_LINK_APPROVAL` | kitInternal | unimplemented |
| `TYPING_SIGNAL` | droppable | droppable |
| unknown / unset | inboxed | inboxed |

- `inboxed`: persist opaque bytes, then ack. Unknown arms are inboxed so an older
  receiver never destroys a newer sender's data.
- `kitInternal`: complete the kit's handler, then ack.
- `droppable`: ephemeral; ack without storage.
- `unimplemented`: log a diagnostic and ack, so one arm cannot wedge the queue.

Delivery is not authorization. The app MUST authorize inboxed content by the
server-stamped user and session-attributed device; payload fields never override
either.

**Typing:** the caller names recipients. `TypingSignal.context_id` is opaque and
MUST NOT be parsed or used to derive recipients. `STARTED` refreshes the sender
device's short-lived state, `STOPPED` clears it, other states are ignored. Typing
state is in-memory, throttled and expiring.

## Inbox

| Field | Type | Meaning |
|---|---|---|
| `id` | integer | Local row id for `consume` / `discard`. |
| `kind` | string | Payload arm, or the unknown/unset marker. |
| `senderUserId` | string | Server-stamped `sender_id`. |
| `senderDeviceId` | nullable string | Device UUID whose session decrypted the message. |
| `modelKey`, `entryId` | nullable string | From `AppEntry`; null for other arms. |
| `sentAt` | nullable integer | Declared timestamp, clamped. |
| `payload` | bytes | Opaque serialized payload. |

```text
peek(limit = 50)     -> [InboxRecord]
consume(ids)         -> void
discard(ids, reason) -> void
depth()              -> integer
```

1. Only the receive path writes the inbox. No public insert, cursor or retry
   counter.
2. Rows leave only through `consume`, `discard`, or the whole-store wipe in a
   device wipe.
3. `peek` returns the oldest pending rows in stable order, with no side effects.
4. `consume` is idempotent and accepts partial batches.
5. `discard` logs its reason to the kit's security log.
6. The envelope id is unique while a row is pending, so a redelivery neither
   duplicates nor re-notifies. Consumed ids are not kept: app merge MUST be
   idempotent.
7. `depth` is exposed and the app MUST monitor it.

There is no skip cursor and no eviction. A row the app cannot process is left
pending (transient), processed and consumed, or discarded with a reason. An
undrained inbox eventually makes persistence fail, the kit stops acking, and the
server queue fills; the app must surface abnormal depth first.

**App drain:** `peek` a bounded batch; validate and decode; authorize and merge
from the identity fields; write entries; `consume` only after the writes
complete; `discard` only for permanent rejection; repeat until a batch is not
full. No transaction spans entry writes and `consume`, so a crash replays the
row. Notification policy runs after the app commits.

## Entry store

```text
put(model, entry)
all(model)
erase(model, id)
```

`StoredEntry` holds the app-chosen id, timestamp, session-attributed author
device, opaque payload, and nullable `localMetadata`, an app-owned sidecar
stored verbatim and never read, put in an `AppEntry`, or sent.

`erase` deletes under `secure_delete`, then checkpoints and truncates the WAL so
the content is unrecoverable. Erasing a missing entry is a no-op. Both kits
enable `secure_delete` on every database, including an app-supplied driver.

Merge belongs to the app: `APPEND` keeps the first write per entry id, `REPLACE`
keeps the highest `(sentAt, authorDeviceId)`. Expiry is the app calling `erase`;
no kit enforces a TTL in payload data.

## Send

```text
send(recipientUserIds, modelKey, entryId, sentAt, payloadBytes)
```

The only app payload send. The kit resolves each named user's devices, adds the
sender's other devices, excludes the sending device, encrypts per device and
uploads one envelope per device. It MUST NOT broaden or substitute recipients; a
recipient without usable keys is skipped or reported. The caller validates size
and schema. A successful return does not prove every device received it.

## Attachments

`uploadAttachment(plaintext)` encrypts in the kit (AES-256-GCM, fresh key and
nonce each time), uploads ciphertext, and returns the server id with
`contentKey` and `nonce`. `downloadDecryptedAttachment(id, contentKey, nonce)`
returns plaintext and keeps no decrypted copy. Attachment metadata travels in
the app's payload.

## Push drain and events

`processPendingMessages(timeout)` connects or reuses the receive path, waits
within the budget, and returns the number of envelopes processed (redeliveries
included). It does not touch the inbox or event stream. It returns `0` when it
cannot connect, so zero does not mean the server queue is empty. Overlapping
drains may coalesce but MUST NOT duplicate app events for one inbox insert.

`friendsChanged` is a payload-free wake-up; the host then calls `getFriends`.
Debug output is pull-only via `getDebugLog`.

## Login

`login(username, password)` returns:

| Outcome | Meaning | State after |
|---|---|---|
| `existingDevice` | Stored local device logged in | Authenticated, device-scoped |
| `newDevice` | No stored local device | Logged out |
| `deviceMismatch` | Server no longer knows the stored device | Logged out |
| `invalidCredentials` | User-scoped login returned 401/403 (incl. unknown username) | Logged out |
| `userNotFound` | A login returned 404 | Logged out |

Other HTTP statuses throw. After `newDevice` call `loginAndProvision`; after
`deviceMismatch` call `wipeDevice` first. `loginAndProvision` ends in
`pendingApproval` if another device can approve the link, else `authenticated`.
`register` and `loginAndProvision` store the device `login` checks.

## Known gaps

- Swift cannot receive `DEVICE_LINK_APPROVAL`.
- No kit cross-checks `sender_id` against the device owner (risk: mislabelled,
  never forged, messages).
- Linked devices do not learn friendships created after linking.
- Device announcements have no replay protection.
- Partial-recipient send failures are invisible to the app.
- Consumed inbox envelope ids have no durable tombstone.
