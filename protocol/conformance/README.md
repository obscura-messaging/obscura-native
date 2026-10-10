# Wire conformance vectors

`wire.json` is normative for the cases it covers. Both kits load it in their
unit suites (`WireConformanceTest.kt`, `WireConformanceTests.swift`) and check
their `WireCodec` against it.

| Array | Checks |
|---|---|
| `messageTypes` | `ClientMessage.payload` arm ↔ app-facing kind |
| `typingStates` | `TypingSignal.state` ↔ app-facing state |
| `roundTrip` | `AppEntry` encode/decode preserves values (JSON compared by value, not bytes) |

`validate.py` (`just protocol-vectors`) checks only well-formedness: strict
JSON, required keys, no unregistered files, and that each file is referenced
from `docs/KIT_API.md`. A new vector file must be registered in `validate.py`.

Rules:

- Change a cross-platform encoding → add or update a vector and both kit suites
  in the same change.
- Keep application model fixtures out; app routing and merge are tested in
  `obscura-pix`.
