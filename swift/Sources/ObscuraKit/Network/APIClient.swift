import Foundation
import CryptoKit

/// HTTP API Client for Obscura Server
public actor APIClient {
    public let baseURL: String
    private var token: String?

    public init(baseURL: String) {
        // Cleartext HTTP is allowed only for loopback (containerized test server).
        let isLoopback = baseURL.hasPrefix("http://localhost")
            || baseURL.hasPrefix("http://127.0.0.1")
            || baseURL.hasPrefix("http://[::1]")
        precondition(baseURL.hasPrefix("https://") || isLoopback,
                     "API URL must use HTTPS (cleartext allowed only for loopback)")
        self.baseURL = baseURL
    }

    // MARK: - Token Management

    public func setToken(_ t: String) { token = t }
    public func getToken() -> String? { token }
    public func clearToken() { token = nil }

    public nonisolated static func decodeJWT(_ jwt: String) -> [String: Any]? {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2 else { return nil }

        var base64 = String(parts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }

        guard let data = Data(base64Encoded: base64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return json
    }

    public nonisolated static func extractUserId(_ jwt: String) -> String? {
        guard let payload = decodeJWT(jwt) else { return nil }
        return (payload["sub"] as? String)
            ?? (payload["user_id"] as? String)
            ?? (payload["userId"] as? String)
            ?? (payload["id"] as? String)
    }

    public nonisolated static func extractDeviceId(_ jwt: String) -> String? {
        guard let payload = decodeJWT(jwt) else { return nil }
        return (payload["device_id"] as? String)
            ?? (payload["deviceId"] as? String)
    }

    // MARK: - Generic Request

    public struct APIError: LocalizedError {
        public let status: Int
        public let body: String

        public var errorDescription: String? {
            "HTTP \(status)"
        }
    }

    private func request(
        _ path: String,
        method: String = "GET",
        body: Data? = nil,
        contentType: String = "application/json",
        auth: Bool = true,
        extraHeaders: [String: String] = [:]
    ) async throws -> (Data, HTTPURLResponse) {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw APIError(status: 0, body: "Invalid URL: \(baseURL)\(path)")
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        if auth, let token = token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }

        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError(status: 0, body: "Not HTTP response")
        }

        guard (200..<300).contains(httpResponse.statusCode) else {
            let bodyStr = String(data: data, encoding: .utf8) ?? ""
            throw APIError(status: httpResponse.statusCode, body: bodyStr)
        }

        return (data, httpResponse)
    }

    private let decoder = JSONDecoder()

    private func jsonRequest<T: Decodable>(_ type: T.Type, _ path: String, method: String = "GET", body: (any Encodable)? = nil, auth: Bool = true) async throws -> T {
        var bodyData: Data? = nil
        if let body = body {
            bodyData = try JSONEncoder().encode(body)
        }
        let (data, _) = try await request(path, method: method, body: bodyData, auth: auth)
        if data.isEmpty {
            // Some endpoints return empty body on success; try decoding anyway
        }
        return try decoder.decode(T.self, from: data)
    }

    /// Fire-and-forget JSON request (for endpoints where we don't need the response body)
    private func jsonRequestVoid(_ path: String, method: String = "GET", body: (any Encodable)? = nil, auth: Bool = true) async throws {
        var bodyData: Data? = nil
        if let body = body {
            bodyData = try JSONEncoder().encode(body)
        }
        _ = try await request(path, method: method, body: bodyData, auth: auth)
    }

    // MARK: - Auth

    public func registerUser(_ username: String, _ password: String) async throws -> AuthResponse {
        try await jsonRequest(AuthResponse.self, "/v1/users", method: "POST",
                              body: ["username": username, "password": password], auth: false)
    }

    public func loginWithDevice(_ username: String, _ password: String, deviceId: String? = nil) async throws -> AuthResponse {
        var body: [String: String] = ["username": username, "password": password]
        if let deviceId = deviceId { body["deviceId"] = deviceId }
        return try await jsonRequest(AuthResponse.self, "/v1/sessions", method: "POST", body: body, auth: false)
    }

    public func refreshSession(_ refreshToken: String) async throws -> AuthResponse {
        try await jsonRequest(AuthResponse.self, "/v1/sessions/refresh", method: "POST",
                              body: ["refreshToken": refreshToken], auth: false)
    }

    public func logout(_ refreshToken: String) async throws {
        try await jsonRequestVoid("/v1/sessions", method: "DELETE", body: ["refreshToken": refreshToken])
    }

    // MARK: - Devices

    public func provisionDevice(name: String, identityKey: String, registrationId: Int,
                                 signedPreKey: SignedPreKeyUpload, oneTimePreKeys: [PreKeyUpload]) async throws -> AuthResponse {
        struct ProvisionRequest: Encodable {
            let name: String
            let identityKey: String
            let registrationId: Int
            let signedPreKey: SignedPreKeyUpload
            let oneTimePreKeys: [PreKeyUpload]
        }
        let body = ProvisionRequest(name: name, identityKey: identityKey, registrationId: registrationId,
                                     signedPreKey: signedPreKey, oneTimePreKeys: oneTimePreKeys)
        return try await jsonRequest(AuthResponse.self, "/v1/devices", method: "POST", body: body)
    }

    public func listDevices() async throws -> [DeviceResponse] {
        let (data, _) = try await request("/v1/devices")
        // Server may return a bare array or a wrapped {"devices": [...]}
        if let devices = try? decoder.decode([DeviceResponse].self, from: data) {
            return devices
        }
        let wrapper = try decoder.decode(DeviceListWrapper.self, from: data)
        return wrapper.devices
    }

    private struct DeviceListWrapper: Decodable {
        let devices: [DeviceResponse]
    }

    public func getDevice(_ deviceId: String) async throws -> DeviceResponse {
        try await jsonRequest(DeviceResponse.self, "/v1/devices/\(urlEncode(deviceId))")
    }

    public func deleteDevice(_ deviceId: String) async throws {
        try await jsonRequestVoid("/v1/devices/\(urlEncode(deviceId))", method: "DELETE")
    }

    // MARK: - Keys

    public func uploadDeviceKeys(identityKey: String, registrationId: Int,
                                  signedPreKey: SignedPreKeyUpload, oneTimePreKeys: [PreKeyUpload]) async throws {
        struct KeysRequest: Encodable {
            let identityKey: String
            let registrationId: Int
            let signedPreKey: SignedPreKeyUpload
            let oneTimePreKeys: [PreKeyUpload]
        }
        let body = KeysRequest(identityKey: identityKey, registrationId: registrationId,
                                signedPreKey: signedPreKey, oneTimePreKeys: oneTimePreKeys)
        try await jsonRequestVoid("/v1/devices/keys", method: "POST", body: body)
    }

    public func fetchPreKeyBundles(_ userId: String) async throws -> [PreKeyBundleResponse] {
        let (data, _) = try await request("/v1/users/\(urlEncode(userId))")
        return try decoder.decode([PreKeyBundleResponse].self, from: data)
    }

    // MARK: - Messaging (Protobuf)

    @discardableResult
    public func sendMessage(_ protobufData: Data) async throws -> Data {
        let hash = SHA256.hash(data: protobufData)
        let idempotencyKey = Array(hash).prefix(16).map { String(format: "%02x", $0) }.joined()
        let (data, _) = try await request("/v1/messages", method: "POST", body: protobufData,
                              contentType: "application/x-protobuf",
                              extraHeaders: ["Idempotency-Key": idempotencyKey])
        return data
    }

    // MARK: - Attachments

    public func uploadAttachment(_ blob: Data) async throws -> AttachmentResponse {
        let (data, _) = try await request("/v1/attachments", method: "POST", body: blob,
                                          contentType: "application/octet-stream")
        return try decoder.decode(AttachmentResponse.self, from: data)
    }

    public func fetchAttachment(_ id: String) async throws -> Data {
        let (data, _) = try await request("/v1/attachments/\(urlEncode(id))")
        return data
    }

    // MARK: - Gateway

    public func fetchGatewayTicket() async throws -> String {
        let response = try await jsonRequest(GatewayTicketResponse.self, "/v1/gateway/ticket", method: "POST")
        return response.ticket
    }

    // MARK: - Push

    public func registerPushToken(_ token: String, type: String = "apns") async throws {
        try await jsonRequestVoid("/v1/push-tokens", method: "PUT", body: ["token": token, "type": type])
    }

    // MARK: - Helpers

    private func urlEncode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? value
    }
}
