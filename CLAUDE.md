# Obscura Native

- Read [`docs/KIT_API.md`](docs/KIT_API.md) before changing cross-platform
  behaviour. It is the only place shared kit rules live; platform docs must not
  restate them.
- Platform guidance: [`kotlin/README.md`](kotlin/README.md),
  [`swift/README.md`](swift/README.md). Commands and test setup:
  [`CONTRIBUTING.md`](CONTRIBUTING.md).
- The kits agree on the wire format and `KIT_API.md`, not on internal design.
  Do not copy one kit's structure into the other.
- `obscura-client-web` is a throwaway proof of concept, not a reference.

## Protocol changes

- Client content: edit `protocol/obscura/client/v1/client.proto` and
  `protocol/conformance/wire.json`, then update both kits' conformance suites in
  the same change. Kotlin generates bindings at build time; Swift bindings are
  checked in, so run `swift/scripts/gen-proto.sh`.
- Transport: change `obscura-proto`, bump the `proto/` submodule, regenerate.
- Never add a platform-local copy of either schema.

## Server

API `https://obscura.barrelmaker.dev`, spec at `/openapi.yaml` (the source of
truth for status codes and body shapes). Non-obvious behaviour:

- Passwords shorter than 12 characters get HTTP 400.
- Rate limits are per client IP: 10 req/s (burst 20) generally, 1 req/s
  (burst 3) for register, login, logout and refresh.
- The API port has no `/health`; probe `/openapi.yaml`.
- Device provisioning verifies the signed prekey's XEdDSA signature, so test
  keys must be real.
- The server has no Kyber prekeys.
