import Foundation

actor APIClient {
    nonisolated let baseURL: URL
    private nonisolated let token = Locked<String?>(nil)
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(baseURL: String) throws {
        guard let url = URL(string: baseURL), let host = url.host?.lowercased(), !host.isEmpty,
              url.user == nil, url.password == nil,
              url.scheme?.lowercased() == "https"
                || (url.scheme?.lowercased() == "http" && ["localhost", "127.0.0.1", "[::1]", "::1"].contains(host)) else {
            throw ObscuraError.invalidArgument("apiURL must be https (http only for loopback): \(baseURL)")
        }
        self.baseURL = url
    }

    nonisolated func setToken(_ token: String?) {
        self.token.withLock { $0 = token }
    }

    // MARK: - JWT

    static func claims(_ jwt: String) -> [String: Any]? {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var base64 = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        guard let data = Data(base64Encoded: base64) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func userId(_ jwt: String) -> String? { claims(jwt)?["sub"] as? String }
    static func deviceId(_ jwt: String) -> String? { claims(jwt)?["device_id"] as? String }
    static func expiry(_ jwt: String) -> TimeInterval? { claims(jwt)?["exp"] as? TimeInterval }

    // MARK: - Transport

    static let maxRetries = 2

    /// Seconds to wait before retrying a 429/503. The server floors sub-second waits to 0.
    static func retryDelay(retryAfter: String?) -> UInt64 {
        let seconds = retryAfter.flatMap { UInt64($0.trimmingCharacters(in: .whitespaces)) } ?? 1
        return min(max(seconds, 1), 10)
    }

    private func request(_ path: String, method: String = "GET", query: [URLQueryItem] = [], body: Data? = nil,
                         contentType: String = "application/json", headers: [String: String] = [:],
                         bearer: String? = nil) async throws -> Data {
        try await exchange(path, method: method, query: query, body: body, contentType: contentType, headers: headers,
                           bearer: bearer).data
    }

    private func exchange(_ path: String, method: String, query: [URLQueryItem] = [], body: Data? = nil,
                          contentType: String = "application/json", headers: [String: String] = [:],
                          bearer: String? = nil) async throws -> (data: Data, status: Int) {
        guard var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            throw ObscuraError.invalidArgument("path \(path)")
        }
        if !query.isEmpty { components.queryItems = query }
        guard let url = components.url else { throw ObscuraError.invalidArgument("path \(path)") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        if let token = bearer ?? token.withLock({ $0 }) { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        for (field, value) in headers { request.setValue(value, forHTTPHeaderField: field) }

        var retries = 0
        while true {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw ObscuraError.protocolViolation("non-HTTP response for \(path)")
            }
            if (200..<300).contains(http.statusCode) { return (data, http.statusCode) }
            if [429, 503].contains(http.statusCode), retries < Self.maxRetries {
                retries += 1
                let delay = Self.retryDelay(retryAfter: http.value(forHTTPHeaderField: "Retry-After"))
                try await Task.sleep(nanoseconds: delay * 1_000_000_000)
                continue
            }
            throw ObscuraError.http(status: http.statusCode, body: String(decoding: data, as: UTF8.self))
        }
    }

    private func json<T: Decodable>(_ path: String, method: String = "GET", body: (any Encodable)? = nil,
                                    bearer: String? = nil) async throws -> T {
        try decoder.decode(T.self, from: await request(path, method: method, body: body.map { try encoder.encode($0) },
                                                       bearer: bearer))
    }

    private func call(_ path: String, method: String, body: any Encodable) async throws {
        _ = try await request(path, method: method, body: encoder.encode(body))
    }

    // MARK: - Endpoints

    func registerUser(_ username: String, _ password: String) async throws -> AuthResponse {
        try await json("v1/users", method: "POST", body: ["username": username, "password": password])
    }

    func login(_ username: String, _ password: String, deviceId: String?) async throws -> AuthResponse {
        var body = ["username": username, "password": password]
        body["deviceId"] = deviceId
        return try await json("v1/sessions", method: "POST", body: body)
    }

    func refreshSession(_ refreshToken: String) async throws -> AuthResponse {
        try await json("v1/sessions/refresh", method: "POST", body: ["refreshToken": refreshToken])
    }

    /// Takes the session's access token explicitly: the client has already forgotten it.
    func logout(_ refreshToken: String, token: String) async throws {
        _ = try await request("v1/sessions", method: "DELETE", body: encoder.encode(["refreshToken": refreshToken]), bearer: token)
    }

    /// Takes a user-scoped token explicitly: it is never the client's token.
    func createDevice(_ device: CreateDeviceRequest, bearer: String) async throws -> AuthResponse {
        try await json("v1/devices", method: "POST", body: device, bearer: bearer)
    }

    /// The account's devices. `bearer` is a user-scoped token; nil uses the device's own token.
    func listDevices(bearer: String? = nil) async throws -> [DeviceResponse] {
        struct Directory: Decodable { let devices: [DeviceResponse] }
        let response: Directory = try await json("v1/devices", bearer: bearer)
        return response.devices
    }

    func deleteDevice(_ deviceId: String) async throws {
        _ = try await request("v1/devices/\(deviceId)", method: "DELETE")
    }

    func resetAccount(_ username: String, _ password: String) async throws {
        _ = try await request("v1/account/reset", method: "POST",
                              body: encoder.encode(["username": username, "password": password]))
    }

    func uploadPreKeys(_ keys: PreKeysUpload) async throws {
        try await call("v1/devices/keys", method: "POST", body: keys)
    }

    /// Consumes one one-time prekey on every device of `userId`.
    func fetchPreKeyBundles(_ userId: String) async throws -> [PreKeyBundleResponse] {
        try await json("v1/users/\(userId)")
    }

    /// Consumes one one-time prekey on that device only.
    func fetchPreKeyBundle(_ userId: String, deviceId: String) async throws -> PreKeyBundleResponse {
        try await json("v1/users/\(userId)/devices/\(deviceId)")
    }

    func createLinkSession(bearer: String) async throws -> LinkSessionResponse {
        try await json("v1/link-sessions", method: "POST", bearer: bearer)
    }

    /// The primary's body once the session is approved; nil if it was not approved within `wait` seconds.
    func awaitLinkSession(_ id: String, wait: Int, bearer: String) async throws -> Data? {
        let response = try await exchange("v1/link-sessions/\(id)", method: "GET",
                                          query: [URLQueryItem(name: "wait", value: "\(wait)")], bearer: bearer)
        return response.status == 204 ? nil : response.data
    }

    func approveLinkSession(_ id: String, envelope: Data) async throws {
        _ = try await request("v1/link-sessions/\(id)", method: "PUT", body: envelope, contentType: "application/octet-stream")
    }

    /// One fresh `Idempotency-Key` per call, kept across its 429/503 retries.
    func sendMessage(_ protobuf: Data) async throws -> Data {
        let idempotencyKey = UUID().uuidString.lowercased()
        return try await request("v1/messages", method: "POST", body: protobuf, contentType: "application/x-protobuf",
                                 headers: ["Idempotency-Key": idempotencyKey])
    }

    func uploadAttachment(_ blob: Data) async throws -> AttachmentResponse {
        try decoder.decode(AttachmentResponse.self, from: await request(
            "v1/attachments", method: "POST", body: blob, contentType: "application/octet-stream"))
    }

    func fetchAttachment(_ id: String) async throws -> Data {
        try await request("v1/attachments/\(id)")
    }

    func fetchGatewayTicket() async throws -> String {
        let response: GatewayTicketResponse = try await json("v1/gateway/ticket", method: "POST")
        return response.ticket
    }

    func registerPushToken(_ pushToken: String) async throws {
        try await call("v1/push-tokens", method: "PUT", body: ["token": pushToken, "type": "apns"])
    }
}
