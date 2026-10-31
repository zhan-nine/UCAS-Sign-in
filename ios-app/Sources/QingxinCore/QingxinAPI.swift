// SPDX-License-Identifier: AGPL-3.0-only
// Ported from android-app/.../network/QingxinApiService.kt, 2026-10-02.
import Foundation
import CoreFoundation

public struct EndpointConfiguration: Codable, Equatable, Sendable {
    public var apiBaseURL: String
    public var verificationURL: String
    public var lectureURL: String
    public init(apiBaseURL: String = "", verificationURL: String = "", lectureURL: String = "") {
        self.apiBaseURL = apiBaseURL; self.verificationURL = verificationURL; self.lectureURL = lectureURL
    }
    public func validatedBaseURL() throws -> URL {
        let text = apiBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: text), let host = components.host, !host.isEmpty,
              ["https", "http"].contains(components.scheme?.lowercased() ?? ""),
              components.user == nil, components.password == nil, components.query == nil, components.fragment == nil,
              let url = components.url else {
            throw QingxinError.configuration("请在设置中填写有效的 API 根地址")
        }
        return url.absoluteString.hasSuffix("/") ? url : URL(string: url.absoluteString + "/")!
    }
}

public struct QingxinAPI: Sendable {
    public let configuration: EndpointConfiguration
    private let transport: URLSession
    // Keep upstream protocol identifiers until actual iOS backend compatibility is verified.
    private static let agent = "student_5.0.1.2_android_12_20_100000000000000_110000"
    private static let loginAgent = "student_5.0.1.2_android_12_20__110000"

    public init(configuration: EndpointConfiguration, transport: URLSession? = nil) {
        self.configuration = configuration
        if let transport { self.transport = transport }
        else {
            let c = URLSessionConfiguration.ephemeral
            c.requestCachePolicy = .reloadIgnoringLocalCacheData; c.urlCache = nil
            c.timeoutIntervalForRequest = 15; c.timeoutIntervalForResource = 20
            self.transport = URLSession(configuration: c)
        }
    }

    public func request(path: String, form: [(String, String)]? = nil, session: SchoolSession? = nil,
                        query: [URLQueryItem] = [], login: Bool = false) throws -> URLRequest {
        let base = try configuration.validatedBaseURL()
        guard let url = URL(string: path, relativeTo: base)?.absoluteURL,
              var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw QingxinError.configuration("接口地址格式错误")
        }
        if !query.isEmpty { parts.queryItems = (parts.queryItems ?? []) + query }
        guard let final = parts.url else { throw QingxinError.configuration("接口地址格式错误") }
        var request = URLRequest(url: final)
        request.setValue(login ? Self.loginAgent : Self.agent, forHTTPHeaderField: "User-Agent")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let session { request.setValue(session.sessionId, forHTTPHeaderField: "sessionId") }
        if let form {
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = Self.formBody(form).data(using: .utf8)
        }
        return request
    }

    public static func formBody(_ fields: [(String, String)]) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._*")
        func encode(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: allowed) ?? "" }
        return fields.map { encode($0.0) + "=" + encode($0.1) }.joined(separator: "&")
    }

    private func json(_ request: URLRequest) async throws -> [String: Any] {
        let (data, response) = try await transport.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw QingxinError.invalidResponse("学校返回了无效响应") }
        guard (200..<300).contains(http.statusCode) else { throw QingxinError.http(http.statusCode) }
        guard let result = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw QingxinError.invalidResponse("学校返回了无法识别的数据")
        }
        let message = [result["ERRMSG"], result["msg"], result["message"]].map(Self.text).joined(separator: " ")
        if request.value(forHTTPHeaderField: "sessionId") != nil,
           message.range(of: "未登录|登录.*(失效|过期)|session.*(invalid|expired)", options: [.regularExpression, .caseInsensitive]) != nil {
            throw QingxinError.sessionExpired
        }
        return result
    }

    public func login(identity: String, password: String) async throws -> SchoolSession {
        guard !configuration.verificationURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw QingxinError.configuration("请先填写学校 verificationUrl 模板")
        }
        guard !identity.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !password.isEmpty, password.count <= 80 else {
            throw QingxinError.configuration("请输入有效的学校账号和密码")
        }
        let req = try request(path: "user/login.action", form: [
            ("phone", identity.trimmingCharacters(in: .whitespacesAndNewlines)), ("password", password),
            ("verificationType", "1"), ("verificationUrl", configuration.verificationURL), ("userLevel", "1")
        ], login: true)
        return try Self.parseSession(await json(req))
    }
    public func resume(identity: String) async throws -> SchoolSession {
        let req = try request(path: "user/login.action", form: [
            ("phone", identity), ("password", ""), ("verificationType", "2"), ("verificationUrl", ""), ("userLevel", "1")
        ], login: true)
        return try Self.parseSession(await json(req))
    }
    public func serverTimestamp() async throws -> Int64 {
        var req = try request(path: "common/get_timestamp.do?id=0", form: [])
        req.timeoutInterval = 6
        let value = try await json(req)
        guard Self.text(value["STATUS"]) == "0", let timestamp = value["timestamp"] as? NSNumber,
              CFGetTypeID(timestamp) != CFBooleanGetTypeID(),
              (1_000_000_000_000..<8_640_000_000_000_001).contains(timestamp.int64Value) else {
            throw QingxinError.invalidResponse("学校校时数据异常，请重试")
        }
        return timestamp.int64Value
    }
    public func courses(session: SchoolSession, dateKey: String) async throws -> CourseQuery {
        let form = [("id", session.userId), ("dateStr", dateKey)]
        let daily = try await json(request(path: "course/get_stu_course_sched.action", form: form, session: session))
        let list = Self.parseCourses(daily["result"], day: dateKey)
        if Self.text(daily["STATUS"]) == "0", !list.isEmpty { return CourseQuery(courses: list, fromWeeklyFallback: false) }
        let weekly = try await json(request(path: "course/get_stu_course_sched_week.action", form: form, session: session))
        // Upstream documents inconsistent STATUS semantics for this endpoint. A real result array is authoritative.
        guard let days = weekly["result"] as? [[String: Any]] else {
            throw QingxinError.invalidResponse("学校暂未返回可用课表，请重新查询")
        }
        let all = days.flatMap { day -> [Course] in
            let key = SchoolDate.normalize(Self.text(day["dateStr"]))
            guard key.count == 8 else { return [] }
            return Self.parseCourses(day["schedData"], day: key)
        }
        let today = all.filter { $0.day == dateKey }
        return CourseQuery(courses: today.isEmpty ? all : today, fromWeeklyFallback: today.isEmpty && !all.isEmpty)
    }
    public func qrURL(code: SignCode, timestamp: Int64) throws -> URL {
        try request(path: "course/stu_scan_sign.action", query: [
            URLQueryItem(name: code.parameter, value: code.value), URLQueryItem(name: "timestamp", value: String(timestamp))
        ]).url!
    }
    public func sign(session: SchoolSession, code: SignCode, timestamp: Int64) async throws -> SignResult {
        var req = try request(path: "course/stu_scan_sign.action", session: session, query: [
            URLQueryItem(name: code.parameter, value: code.value), URLQueryItem(name: "timestamp", value: String(timestamp)),
            URLQueryItem(name: "id", value: session.userId)
        ])
        req.timeoutInterval = 10
        return Self.parseSign(try await json(req))
    }
    public static func text(_ value: Any?) -> String {
        guard let value, !(value is NSNull) else { return "" }
        return String(describing: value).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    public static func parseSession(_ json: [String: Any]) throws -> SchoolSession {
        guard text(json["STATUS"]) == "0" else { throw QingxinError.loginRejected }
        guard let result = json["result"] as? [String: Any] else { throw QingxinError.invalidResponse("学校登录返回不完整") }
        let id = text(result["id"]), session = text(result["sessionId"]), student = text(result["studentNo"])
        guard !id.isEmpty, !session.isEmpty, !student.isEmpty else {
            throw QingxinError.invalidResponse("学校登录未返回完整身份，已停止操作")
        }
        return SchoolSession(userId: id, sessionId: session, studentNo: student)
    }
    public static func parseCourses(_ value: Any?, day: String) -> [Course] {
        guard let rows = value as? [[String: Any]] else { return [] }
        return rows.map { row in
            let name = text(row["courseName"])
            return Course(scheduleId: text(row["id"]), uuid: text(row["uuid"]), name: name.isEmpty ? "未命名课程" : name,
                          teacher: text(row["teacherName"]), beginTime: text(row["classBeginTime"]),
                          endTime: text(row["classEndTime"]), day: SchoolDate.normalize(day), signed: text(row["signStatus"]) == "1",
                          courseId: text(row["courseId"]), courseNum: text(row["courseNum"]))
        }
    }
    public static func parseSign(_ json: [String: Any]) -> SignResult {
        let result = json["result"] as? [String: Any] ?? [:]
        let message = [result["msg"], json["ERRMSG"], json["msg"], json["message"]].map(text).first { !$0.isEmpty } ?? ""
        if message.range(of: "(二维码|签到码).*(失效|过期)|timestamp.*(invalid|expired)", options: [.regularExpression, .caseInsensitive]) != nil {
            return SignResult(outcome: .expired, message: "学校提示签到码已失效，请刷新后重试")
        }
        let success = json["success"] == nil || (json["success"] as? Bool) == true
        let error = text(json["ERRCODE"])
        if text(json["STATUS"]) == "0", error.isEmpty || error == "0", text(result["stuSignStatus"]) == "1", success {
            return SignResult(outcome: .signed, message: "签到成功")
        }
        if message.contains("已签到") || message.contains("重复签到") {
            return SignResult(outcome: .rejected, message: "学校提示已签到，请刷新课程状态")
        }
        if message.contains("未选") || message.contains("不属于") {
            return SignResult(outcome: .rejected, message: "学校提示当前身份未选此课程")
        }
        if text(json["STATUS"]) != "0" || message.range(of: "未在上课时间|不在.*签到时间|不是上课时间", options: .regularExpression) != nil {
            return SignResult(outcome: .rejected, message: "学校未完成签到，请确认上课时间和课程状态")
        }
        return SignResult(outcome: .unknown, message: "学校未明确确认签到完成，请刷新课程状态后再决定是否重试")
    }
}
