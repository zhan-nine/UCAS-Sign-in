// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import UserNotifications
import QingxinCore

@MainActor enum ReminderService {
    private static var revision = 0
    static func permission() async throws -> Bool {
        try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }
    static func schedule(_ courses: [Course], enabled: Bool) async {
        revision += 1
        let token = revision
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests()
        guard token == revision else { return }
        center.removePendingNotificationRequests(withIdentifiers: pending.filter { $0.identifier.hasPrefix("course:") }.map(\.identifier))
        guard enabled else { return }
        let sorted = courses.sorted { ($0.begin ?? .distantFuture) < ($1.begin ?? .distantFuture) }
        for course in sorted.prefix(50) where !course.signed {
            guard token == revision else { return }
            guard let begin = course.begin else { continue }
            let remind = begin.addingTimeInterval(-15 * 60)
            guard remind > Date() else { continue }
            let content = UNMutableNotificationContent()
            content.title = "\(course.name) · 即将上课"
            content.body = "\(SchoolDate.time(begin)) 开始，打开清新签到查看课程。"
            content.sound = .default
            let identifier = "course:\(course.id):\(token)"
            var components = SchoolDate.calendar.dateComponents([.year, .month, .day, .hour, .minute], from: remind)
            components.timeZone = SchoolDate.zone
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
            if token != revision {
                center.removePendingNotificationRequests(withIdentifiers: [identifier])
                return
            }
        }
    }
    static func clear() {
        revision += 1
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
    }
}
