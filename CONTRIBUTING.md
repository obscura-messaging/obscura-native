# Contributing

## Workflow

Never commit directly to `main`. Create a branch, open a pull request, and let
the required CI jobs finish before merging.

`obscura-native` owns the Native kit. Changes that affect the app-facing API
land here first; `obscura-pix` then updates its gitlink to the merged Native
commit in a separate pull request.

Read [`docs/NATIVE_CONTRACT.md`](docs/NATIVE_CONTRACT.md) and
[`docs/KIT_API.md`](docs/KIT_API.md) before changing cross-platform behavior.
Kotlin and Swift share wire behavior, not implementation architecture.

## Prerequisites

Install [`just`](https://github.com/casey/just), JDK 21, Python 3, and
[`buf`](https://buf.build/docs/installation). Swift work additionally requires
macOS, Xcode 16+, Rust stable, and `protoc`.

On macOS:

```bash
brew install just buf protobuf
```

JDK 21 is pinned in [`.java-version`](.java-version). Gradle recipes reject
other Java versions and automatically locate JDK 21 through `java_home` on
macOS.

## Setup

```bash
git clone --recurse-submodules https://github.com/obscura-messaging/obscura-native.git
cd obscura-native
just setup
just doctor
```

For Swift development:

```bash
just doctor-swift
```

The first Swift build or test fetches the pinned libsignal commit and builds
its host FFI. Later runs reuse that output.

## Checks

```bash
just protocol-check
just kotlin-check
just swift-unit       # macOS
just check            # every fast gate on macOS
```

Integration suites require an explicit local server and fail before running if
it is unavailable:

```bash
just kotlin-integration http://localhost:3000
just swift-integration http://localhost:3000
```

The CI workflows use these same recipes. Infrastructure setup remains in
GitHub Actions because it is runner-specific.
