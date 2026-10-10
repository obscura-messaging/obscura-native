# Shared conformance vectors

`wire.json` is the executable cross-kit contract for payload-arm names,
typed typing signals, and representative app payload bytes in `client.proto`.
Both kit suites load the same file and must produce equivalent wire messages.

This directory contains only behavior that both kits must independently
implement. Application-owned routing and merge are tested in `obscura-pix`, not
duplicated as kit conformance engines.

## Rules

- `wire.json` is normative for the cases it covers.
- Add a vector when changing cross-platform encoding.
- Update both kit test suites in the same change.
- Keep application model fixtures out of this directory.
- Use strict JSON with no comments or trailing commas.

The file has three arrays:

| Array | Purpose |
|---|---|
| `messageTypes` | Proto payload-arm to app kind mappings. |
| `typingStates` | `TypingSignal.state` mappings. |
| `roundTrip` | Value-preserving `AppEntry` encode/decode cases. |

Round-trip assertions compare values rather than serialized bytes. Model data is
JSON, where object key order is not meaningful, and Signal authenticates the
payload without requiring a canonical JSON encoding.

## Enforcement

- This repository runs `python3 conformance/validate.py` to validate JSON
  structure, required fields, and non-empty mapping values.
- Each kit runs the shared cases against its own codec; those suites verify the
  semantic wire-to-app mappings.
- A behavior change updates `wire.json` and both kit suites together.
