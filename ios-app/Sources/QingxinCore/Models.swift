// SPDX-License-Identifier: AGPL-3.0-only
// iOS adaptation, 2026-10-02. See repository NOTICE for upstream attribution.
import Foundation

public struct SchoolSession: Codable, Equatable, Sendable {
    public let userId: String
    public let sessionId: String
    public let studentNo: String
}

public struct SignCode: Equatable, Sendable {
    public let parameter: String
    public let value: String
    public var label: String { parameter == "courseSchedId" ? "7 位节次编号" : "32 位二维码标识" }

    public init?(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.range(of: "^[0-9]{7}$", options: .regularExpression) != nil {
            parameter = "courseSchedId"; value = text
        } else {
            let compact = text.replacingOccurrences(of: "-", with: "")
            guard compact.range(of: "^[0-9a-fA-F]{32}$", options: .regularExpression) != nil else { return nil }
            parameter = "timeTableId"; value = compact.uppercased()
        }
    }
}

public struct Course: Codable, Identifiable, Equatable, Sendable {
    public let scheduleId: String
    public let uuid: String
    public let name: String
    public let teacher: String
    public let beginTime: String
    public let endTime: String
    public let day: String
    public var signed: Bool
    public let courseId: String
    public let courseNum: String

    public var id: String { "\(day):\(scheduleId):\(uuid):\(beginTime):\(name)" }
    public var signCode: SignCode? { SignCode(scheduleId) ?? SignCode(uuid) }
    public var begin: Date? { SchoolDate.instant(day: day, time: beginTime) }
    public var end: Date? { SchoolDate.instant(day: day, time: endTime) }

    public init(scheduleId: String, uuid: String = "", name: String, teacher: String = "", beginTime: String,
                endTime: String, day: String, signed: Bool = false, courseId: String = "", courseNum: String = "") {
        self.scheduleId = scheduleId; self.uuid = uuid; self.name = name; self.teacher = teacher
        self.beginTime = beginTime; self.endTime = endTime; self.day = day; self.signed = signed
        self.courseId = courseId; self.courseNum = courseNum
    }

    public func isInWindow(at date: Date) -> Bool {
        guard let begin, let end else { return false }
        return date >= begin.addingTimeInterval(-25 * 60) && date < end
    }
    public func status(at date: Date) -> String {
        if signed { return "已签到" }
        if isInWindow(at: date) { return "可签到" }
        if let end, date >= end { return "已结束" }
        return "待开始"
    }
}

public enum SchoolDate {
    public static let zone = TimeZone(identifier: "Asia/Shanghai")!
    public static var calendar: Calendar {
        var value = Calendar(identifier: .gregorian); value.timeZone = zone; return value
    }
    public static func key(_ date: Date) -> String { formatter("yyyyMMdd").string(from: date) }
    public static func time(_ date: Date?) -> String { date.map { formatter("HH:mm").string(from: $0) } ?? "时间未知" }
    public static func normalize(_ raw: String) -> String {
        String(raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "").replacingOccurrences(of: "/", with: "").prefix(8))
    }
    private static func formatter(_ format: String) -> DateFormatter {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = zone
        f.calendar = calendar; f.dateFormat = format; f.isLenient = false; return f
    }
    public static func instant(day: String, time: String) -> Date? {
        var raw = time.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "：", with: ":").replacingOccurrences(of: "．", with: ".")
        guard !raw.isEmpty else { return nil }
        // An explicit offset is authoritative; bare times use the school's time zone.
        if raw.contains("T"), raw.hasSuffix("Z") || raw.range(of: "[+-][0-9]{2}:[0-9]{2}$", options: .regularExpression) != nil {
            let iso = ISO8601DateFormatter()
            iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = iso.date(from: raw) { return date }
            iso.formatOptions = [.withInternetDateTime]
            if let date = iso.date(from: raw) { return date }
        }
        raw = raw.replacingOccurrences(of: "\\.[0-9]+", with: "", options: .regularExpression)
        for format in ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm"] {
            if let date = formatter(format).date(from: raw) { return date }
        }
        let dayKey = normalize(day)
        guard dayKey.count == 8, let dayDate = formatter("yyyyMMdd").date(from: dayKey), key(dayDate) == dayKey else { return nil }
        if raw.range(of: "^[0-9]{3,4}$", options: .regularExpression) != nil {
            let digits = String(repeating: "0", count: 4 - raw.count) + raw
            raw = String(digits.prefix(2)) + ":" + String(digits.suffix(2))
        } else if raw.range(of: "^[0-9]{6}$", options: .regularExpression) != nil {
            raw = String(raw.prefix(2)) + ":" + String(raw.dropFirst(2).prefix(2)) + ":" + String(raw.suffix(2))
        }
        let parts = raw.split(separator: ":").compactMap { Int($0) }
        guard (2...3).contains(parts.count), raw.split(separator: ":").count == parts.count,
              (0...23).contains(parts[0]), (0...59).contains(parts[1]),
              (0...59).contains(parts.count == 3 ? parts[2] : 0) else { return nil }
        var c = calendar.dateComponents([.year, .month, .day], from: dayDate)
        c.hour = parts[0]; c.minute = parts[1]; c.second = parts.count == 3 ? parts[2] : 0
        return calendar.date(from: c)
    }
}

public struct CourseQuery: Sendable {
    public let courses: [Course]
    public let fromWeeklyFallback: Bool
}

public enum SignOutcome: String, Sendable { case signed, expired, rejected, unknown }
public struct SignResult: Sendable {
    public let outcome: SignOutcome
    public let message: String
}

public struct ClockSample: Sendable {
    public let schoolAtReceiveMs: Int64
    public let receivedAtUptime: TimeInterval
    public init(serverMs: Int64, sentAtUptime: TimeInterval, receivedAtUptime: TimeInterval) throws {
        guard receivedAtUptime >= sentAtUptime,
              (1_000_000_000_000..<8_640_000_000_000_001).contains(serverMs) else {
            throw QingxinError.invalidResponse("学校校时数据异常，请重试")
        }
        schoolAtReceiveMs = serverMs + Int64((receivedAtUptime - sentAtUptime) * 500)
        self.receivedAtUptime = receivedAtUptime
    }
    public func reading(at uptime: TimeInterval) -> (timestamp: Int64, refreshAfter: TimeInterval)? {
        let age = uptime - receivedAtUptime
        guard age >= 0, age < 30 else { return nil }
        return (schoolAtReceiveMs + Int64(age * 1000), min(5, 30 - age))
    }
}

public struct WidgetSnapshot: Codable, Sendable {
    public let day: String
    public let updatedAt: Date
    public let courses: [Course]
    public let isDemo: Bool
    public init(day: String, updatedAt: Date, courses: [Course], isDemo: Bool) {
        self.day = day; self.updatedAt = updatedAt; self.courses = courses; self.isDemo = isDemo
    }
    public func courses(at date: Date) -> [Course] { day == SchoolDate.key(date) ? courses : [] }
}

public enum QingxinError: LocalizedError {
    case configuration(String), invalidResponse(String), loginRejected, sessionExpired, http(Int), invalidCode
    public var requiresReauthentication: Bool {
        if case .sessionExpired = self { return true }
        if case .http(let code) = self { return code == 401 || code == 403 }
        return false
    }
    public var errorDescription: String? {
        switch self {
        case .configuration(let message), .invalidResponse(let message): return message
        case .loginRejected, .sessionExpired: return "学校账号验证失败或会话已过期，请重新登录"
        case .http(let code): return "网络请求失败（\(code)）"
        case .invalidCode: return "请输入 7 位课程编号或 32 位 UUID"
        }
    }
}
