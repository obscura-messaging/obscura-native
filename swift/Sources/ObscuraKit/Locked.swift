import Foundation

final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func withLock<T>(_ body: (inout Value) throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }
}

/// A current value plus streams that yield it on subscribe and on every change.
final class StateStream<Value: Sendable & Equatable>: Sendable {
    private struct State {
        var value: Value
        var subscribers: [UUID: AsyncStream<Value>.Continuation] = [:]
    }

    private let state: Locked<State>

    init(_ initial: Value) {
        state = Locked(State(value: initial))
    }

    var value: Value { state.withLock { $0.value } }

    func set(_ value: Value) {
        state.withLock { state in
            guard state.value != value else { return }
            state.value = value
            for subscriber in state.subscribers.values { subscriber.yield(value) }
        }
    }

    func stream() -> AsyncStream<Value> {
        AsyncStream { continuation in
            let id = UUID()
            state.withLock { state in
                continuation.yield(state.value)
                state.subscribers[id] = continuation
            }
            continuation.onTermination = { [state] _ in
                state.withLock { _ = $0.subscribers.removeValue(forKey: id) }
            }
        }
    }
}
