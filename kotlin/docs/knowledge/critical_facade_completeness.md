---
name: Keep scenario tests on the public facade
description: Supported user flows should use ObscuraClient; raw protobuf is reserved for adversarial wire tests.
type: feedback
---

The rule: **supported user behavior uses the public facade.**

If you find yourself writing `ClientMessage.newBuilder()` in a test, stop. Use
the facade instead: `send()`, `uploadAttachment()`, `announceDevices()`, etc.

`send()` is the only app-payload send. It takes explicit `recipientUserIds` — a
helper that resolves an audience from a friend username would be the kit
deciding an audience from an application concept, which the kit contract
forbids. Test helpers wrap `send()`; they do not get their own facade method.

Raw protobuf is appropriate when the test intentionally creates input that no
public API should expose. Current exceptions are:

- `AckSemanticsTests`: corrupted ciphertext and acknowledgement behavior.
- `FriendGraphIntegrityTests`: forged friend request/response payloads.

All other matches are either unused imports or evidence of a missing facade
method.

**Review matches with:**

```bash
rg -n "obscura\.v1\.|obscura\.client\.v1\.|ClientMessage\.newBuilder|com\.google\.protobuf" \
  lib/src/integrationTest/kotlin/scenarios
```

The generated packages are `obscura.v1` and `obscura.client.v1`; checks must
include both.
