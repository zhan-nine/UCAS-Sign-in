// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import CryptoKit
import QingxinCore
import WidgetKit

enum SnapshotStore {
    static var sharedURL: URL? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "QingxinAppGroup") as? String else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?.appendingPathComponent("today.json")
    }
    private static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CourseCache")
    }
    private static func cacheURL(identity: String, day: String) -> URL {
        let digest = SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent("\(digest)-\(day).json")
    }
    static func read(identity: String, day: String) -> WidgetSnapshot? {
        guard let data = try? Data(contentsOf: cacheURL(identity: identity, day: day)) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }
    static func save(_ snapshot: WidgetSnapshot, identity: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(snapshot)
        try data.write(to: cacheURL(identity: identity, day: snapshot.day), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        if snapshot.day == SchoolDate.key(Date()), let url = sharedURL {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
    static func clear() {
        try? FileManager.default.removeItem(at: directory)
        if let sharedURL { try? FileManager.default.removeItem(at: sharedURL) }
        WidgetCenter.shared.reloadAllTimelines()
    }
}
