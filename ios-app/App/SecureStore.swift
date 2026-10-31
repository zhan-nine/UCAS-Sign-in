// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Security
import QingxinCore

enum SecureStore {
    private static let service = "com.vel.qingxin.credentials"
    private static func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: key]
    }
    static func read<T: Decodable>(_ key: String, as type: T.Type) throws -> T? {
        var q = query(key); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw StoreError(status) }
        return try JSONDecoder().decode(type, from: data)
    }
    static func save<T: Encodable>(_ value: T, key: String) throws {
        let data = try JSONEncoder().encode(value)
        let q = query(key)
        let update = [kSecValueData as String: data]
        let status = SecItemUpdate(q as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var insert = q; insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let added = SecItemAdd(insert as CFDictionary, nil)
            guard added == errSecSuccess else { throw StoreError(added) }
        } else if status != errSecSuccess { throw StoreError(status) }
    }
    static func remove(_ key: String) { SecItemDelete(query(key) as CFDictionary) }
    struct StoreError: LocalizedError {
        let status: OSStatus
        init(_ status: OSStatus) { self.status = status }
        var errorDescription: String? { "本机安全存储暂不可用（\(status)），请解锁设备后重试" }
    }
}

struct SavedLogin: Codable {
    let identity: String
    let password: String?
    let session: QingxinCore.SchoolSession
}
