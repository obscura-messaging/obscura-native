# Contributing

Never commit to `main`. Branch, open a pull request, and merge only after CI is
green. App-facing API changes land here first; `obscura-pix` then bumps its
gitlink to the merged commit in its own pull request.

## Prerequisites

| For | Install |
|---|---|
| Everything | [`just`](https://github.com/casey/just), Python 3, [`buf`](https://buf.build/docs/installation), JDK 21 (pinned in [`.java-version`](.java-version)) |
| Swift | macOS, Xcode 16+, `rustup` (stable), `protoc` |
| Regenerating Swift protobufs | `protoc-gen-swift` (`brew install swift-protobuf`) |

Gradle recipes run through `scripts/run-with-java-21.sh`, which rejects any
other Java version. On macOS it finds JDK 21 with `java_home` when `JAVA_HOME`
is unset.

```bash
just setup          # init submodules
just doctor         # check Kotlin/protocol tools
just doctor-swift   # check Swift tools (macOS)
```

The first Swift build fetches libsignal at the commit pinned in
`swift/scripts/bootstrap-libsignal.sh` (tag v0.40.0) and builds its FFI into
`swift/vendored/`. Later runs reuse it.

## Checks

```bash
just protocol-check   # buf lint + protocol/conformance/validate.py
just kotlin-check     # protocol-check, unit tests, coverage floor, mavenLocal publish
just swift-unit       # Swift UnitTests target (macOS)
just check            # all of the above (macOS)
just --list           # every recipe
```

CI runs these same recipes.

## Integration tests

```bash
just kotlin-integration http://localhost:3000   # :lib:integrationTest
just swift-integration http://localhost:3000    # Swift ScenarioTests
```

Both fail fast unless `<api>/openapi.yaml` answers. To run a server the way CI
does, start [`obscura-server`](https://github.com/obscura-messaging/obscura-server)
with `docker compose`, then:

- **Raise the rate limits.** Set `OBSCURA_RATE_LIMIT_PER_SECOND=1000`,
  `OBSCURA_RATE_LIMIT_BURST=2000`, `OBSCURA_RATE_LIMIT_AUTH_PER_SECOND=1000`
  and `OBSCURA_RATE_LIMIT_AUTH_BURST=2000`. The defaults (10/s burst 20; auth
  1/s burst 3) trip partway through the suites with HTTP 429.
- **Create the bucket.** The server never creates its S3 bucket. In a fresh
  MinIO, create `test-bucket` or every attachment test fails.
- **Drop client pacing.** Set `AUTH_REQUEST_DELAY_MS=0` (both kits) and
  `SERVER_REQUEST_DELAY_MS=0` (Swift). The defaults pace for production limits.

See `.github/workflows/kotlin.yml` and `swift.yml` for the exact setup.
