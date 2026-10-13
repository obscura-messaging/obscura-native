import Foundation
import GRDB
@testable import ObscuraKit

final class MemorySessionStorage: SessionStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [String: String]?
    enum Operation { case save, load, clear }
    var failing: Operation? {
        get { lock.withLock { failure } }
        set { lock.withLock { failure = newValue } }
    }
    private var failure: Operation?

    init(_ stored: [String: String]? = nil) {
        self.stored = stored
    }

    var session: [String: String]? { lock.withLock { stored } }
    func save(_ session: [String: String]) throws {
        try lock.withLock {
            if failure == .save { throw ObscuraError.storageFailed("save denied") }
            stored = session
        }
    }
    func load() throws -> [String: String]? {
        try lock.withLock {
            if failure == .load { throw ObscuraError.storageFailed("load denied") }
            return stored
        }
    }
    func clear() throws {
        try lock.withLock {
            if failure == .clear { throw ObscuraError.storageFailed("clear denied") }
            stored = nil
        }
    }
}

final class SilentLogger: ObscuraLogger {
    func log(_ message: String) {}
    func decryptFailed(sourceUserId: String, reason: String) {}
    func ackFailed(envelopeId: String, reason: String) {}
    func tokenRefreshFailed(attempt: Int, reason: String) {}
    func preKeyReplenishFailed(reason: String) {}
    func identityChanged(address: String) {}
    func sessionEstablishFailed(userId: String, reason: String) {}
    func databaseError(store: String, operation: String, reason: String) {}
}

func makeClient(apiURL: String = "https://obscura.invalid", database: DatabaseQueue? = nil,
                storage: MemorySessionStorage = MemorySessionStorage()) throws -> ObscuraClient {
    try ObscuraClient(apiURL: apiURL, database: database ?? ObscuraDatabase.inMemory(), deviceName: "test-device",
                      sessionStorage: storage, logger: SilentLogger())
}

func signalStoreWithIdentity() throws -> PersistentSignalStore {
    let store = PersistentSignalStore(db: try ObscuraDatabase.inMemory(), logger: SilentLogger())
    try store.install(SignalKeys.generate())
    return store
}
