import Foundation

public enum TypingState: String, Sendable, Codable {
    case started
    case stopped
}

struct TypingEvent: Sendable {
    let contextId: String
    let senderUserId: String
    let senderDeviceId: String
    let timestamp: UInt64
}

public actor TypingTracker {
    private struct ActiveTyper: Sendable {
        let senderUserId: String
        let senderDeviceId: String
        let expiresAt: UInt64
    }

    private var activeByContext: [String: [ActiveTyper]] = [:]
    private let expiryMs: UInt64 = 5_000

    func receive(_ event: TypingEvent) {
        let now = UInt64(Date().timeIntervalSince1970 * 1_000)
        if event.timestamp < now && now - event.timestamp > expiryMs { return }

        var typers = activeByContext[event.contextId] ?? []
        typers.removeAll { $0.senderDeviceId == event.senderDeviceId }
        typers.append(ActiveTyper(
            senderUserId: event.senderUserId,
            senderDeviceId: event.senderDeviceId,
            expiresAt: now + expiryMs
        ))
        activeByContext[event.contextId] = typers
    }

    func remove(contextId: String, senderDeviceId: String) {
        activeByContext[contextId]?.removeAll { $0.senderDeviceId == senderDeviceId }
        if activeByContext[contextId]?.isEmpty == true {
            activeByContext[contextId] = nil
        }
    }

    /// The users typing in `contextId`, once each however many of their devices are. The host
    /// resolves names and decides whose typing to show.
    public func activeUserIds(contextId: String) -> [String] {
        let now = UInt64(Date().timeIntervalSince1970 * 1_000)
        let live = (activeByContext[contextId] ?? []).filter { $0.expiresAt > now }
        activeByContext[contextId] = live.isEmpty ? nil : live
        var seen = Set<String>()
        return live.map(\.senderUserId).filter { seen.insert($0).inserted }
    }

    public func isActive(contextId: String) -> Bool {
        !activeUserIds(contextId: contextId).isEmpty
    }

    public func clearAll() {
        activeByContext.removeAll()
    }
}

public struct TypingObservation {
    let tracker: TypingTracker
    let contextId: String

    public var values: AsyncStream<[String]> {
        AsyncStream { continuation in
            let task = Task {
                var last: [String] = []
                while !Task.isCancelled {
                    let current = await tracker.activeUserIds(contextId: contextId)
                    if current != last {
                        continuation.yield(current)
                        last = current
                    }
                    try? await Task.sleep(nanoseconds: 300_000_000)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

actor TypingThrottle {
    static let shared = TypingThrottle()
    private var lastSent: [String: Date] = [:]

    func shouldSend(contextId: String, state: TypingState, senderDeviceId: String) -> Bool {
        let key = "\(contextId):\(state.rawValue):\(senderDeviceId)"
        let now = Date()
        if let last = lastSent[key], now.timeIntervalSince(last) < 2 {
            return false
        }
        lastSent[key] = now
        return true
    }
}

final class TypingStateRegistry: @unchecked Sendable {
    static let shared = TypingStateRegistry()
    let tracker = TypingTracker()

    private init() {}
}
