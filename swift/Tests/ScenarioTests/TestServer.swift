import Foundation

/// Server URL for the integration (ScenarioTests) suite. Defaults to the live
/// server; override with the `OBSCURA_TEST_API` env var to point at a locally
/// containerized obscura-server:
///
///   (cd ../obscura-server && docker compose up -d)   # server on :3000
///   OBSCURA_TEST_API=http://localhost:3000 swift test --filter ScenarioTests
///
/// Server source is maintained at github.com/obscura-messaging/obscura-server.
enum TestServer {
    static let apiURL: String =
        ProcessInfo.processInfo.environment["OBSCURA_TEST_API"] ?? "https://obscura.barrelmaker.dev"
}
