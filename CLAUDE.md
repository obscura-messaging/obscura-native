# Obscura Native

Read [`docs/KIT_API.md`](docs/KIT_API.md), the kit contract, before changing
cross-platform behavior.
Platform-specific guidance remains in `kotlin/CLAUDE.md` and
`swift/CLAUDE.md`.

## Boundary

`kotlin/` and `swift/` are native layers for one application, `obscura-pix`.
They must agree on the wire contract and `docs/KIT_API.md`, not on internal
design.

## Shared protocol workflow

The root `proto/` submodule contains only the server/native transport contract.
Client content lives under `protocol/`. Do not add platform-local copies or
submodules. A schema-shape change requires regenerated Swift bindings and both
wire conformance suites.

## Commands

```bash
cd kotlin
JAVA_HOME=/path/to/jdk-21 ./gradlew :lib:test

cd ../swift
./dev.sh test --filter UnitTests
```

Swift builds require macOS. Server-dependent suites must preserve the
platform-specific pacing and cleanup rules.
