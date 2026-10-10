# Obscura Native

Native kits for [`obscura-pix`](https://github.com/obscura-messaging/obscura-pix),
an end-to-end encrypted messenger. Each kit has one consumer, the app; neither
is a general-purpose SDK.

| Path | Contents |
|---|---|
| [`docs/KIT_API.md`](docs/KIT_API.md) | The kit contract. Read it before changing either kit. |
| [`kotlin/`](kotlin/README.md) | Kotlin/JVM kit, used by the Android bridge. |
| [`swift/`](swift/README.md) | Swift package, used by the iOS bridge. |
| `proto/` | Submodule: transport schema shared with `obscura-server`. |
| [`protocol/`](protocol/conformance/README.md) | Client-to-client schema and the shared wire vectors. |

The kits share the wire format and the contract, not their internal design.

```bash
git clone --recurse-submodules https://github.com/obscura-messaging/obscura-native.git
cd obscura-native
just setup && just check   # check needs macOS; use just kotlin-check elsewhere
```

Prerequisites, workflow and integration tests: [`CONTRIBUTING.md`](CONTRIBUTING.md).
