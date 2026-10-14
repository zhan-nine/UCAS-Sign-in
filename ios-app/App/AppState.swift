// SPDX-License-Identifier: AGPL-3.0-only
// Native iOS implementation adapted from UCAS-Sign-in repositories, 2026-10-02.
import Foundation
import Combine
import QingxinCore

@MainActor final class AppState: ObservableObject {
    @Published var configuration: EndpointConfiguration
    @Published private(set) var session: SchoolSession?
    @Published private(set) var courses: [Course] = []
    @Published private(set) var todayList: [Course] = []
    @Published private(set) var todayStatus = "登录后查看今日课程"
    @Published private(set) var todayUpdated: Date?
    @Published var selectedDate = Date()
    @Published private(set) var busy = false
    @Published private(set) var signing = false
    @Published var error: String?
    @Published private(set) var status = "登录后查看今日课程"
    @Published private(set) var result: SignResult?
    @Published private(set) var demo = false
    @Published private(set) var lastUpdated: Date?
    @Published var reminderEnabled = UserDefaults.standard.bool(forKey: "reminderEnabled")
    @Published var autoForeground = UserDefaults.standard.bool(forKey: "autoForeground")
    @Published var excluded: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "excludedCourses") ?? [])
    private var todayExcluded: Set<String> = []
    private var autoPlans: [String: Date] = [:]
    private var autoAttempts: [String: Int] = [:]
    private var confirmedSignatures: Set<String> = []
    private var sample: ClockSample?
    private var generation = 0
    private var refreshSequence = 0
    private var foregroundDay = SchoolDate.key(Date())

    init() {
        // iOS may keep Keychain items after uninstall. A fresh install starts signed out.
        if !UserDefaults.standard.bool(forKey: "hasLaunched") {
            SecureStore.remove("login")
            SnapshotStore.clear()
            UserDefaults.standard.set(true, forKey: "hasLaunched")
        }
        let bundled: EndpointConfiguration?
        if let encoded = Bundle.main.object(forInfoDictionaryKey: "QingxinBootstrap") as? String,
           let data = Data(base64Encoded: encoded) {
            bundled = try? JSONDecoder().decode(EndpointConfiguration.self, from: data)
        } else { bundled = nil }
        configuration = (try? SecureStore.read("configuration", as: EndpointConfiguration.self)) ?? bundled ?? EndpointConfiguration()
        session = (try? SecureStore.read("login", as: SavedLogin.self))?.session
        if session != nil { status = "正在恢复课程" }
        if ProcessInfo.processInfo.arguments.contains("--demo") { enterDemo() }
    }
    var loggedIn: Bool { session != nil || demo }
    var api: QingxinAPI { QingxinAPI(configuration: configuration) }
    var todayCourses: [Course] { todayList.filter { $0.day == SchoolDate.key(Date()) } }
    var currentCourse: Course? {
        let now = Date()
        return todayCourses.first { $0.isInWindow(at: now) } ?? todayCourses.first { ($0.begin ?? .distantPast) > now }
    }
    func exclusionKey(_ course: Course) -> String {
        !course.courseId.isEmpty ? course.courseId : (!course.courseNum.isEmpty ? course.courseNum : course.name)
    }
    func isExcluded(_ course: Course) -> Bool { excluded.contains(exclusionKey(course)) || todayExcluded.contains(course.id) }
    func exclude(_ course: Course, permanently: Bool) {
        if permanently {
            let key = exclusionKey(course)
            if excluded.contains(key) { excluded.remove(key) } else { excluded.insert(key) }
            UserDefaults.standard.set(Array(excluded), forKey: "excludedCourses")
        } else {
            if todayExcluded.contains(course.id) { todayExcluded.remove(course.id) } else { todayExcluded.insert(course.id) }
        }
    }
    func saveConfiguration(_ value: EndpointConfiguration) {
        do {
            _ = try value.validatedBaseURL()
            guard !value.verificationURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw QingxinError.configuration("请填写 verificationUrl 模板")
            }
            try SecureStore.save(value, key: "configuration")
            if value != configuration { logout() }
            configuration = value
        } catch { self.error = error.localizedDescription }
    }
    func login(identity: String, password: String, remember: Bool) async {
        guard !busy else { return }
        let token = generation
        busy = true; error = nil
        defer { if token == generation { busy = false } }
        do {
            let newSession = try await api.login(identity: identity, password: password)
            guard token == generation else { return }
            try SecureStore.save(SavedLogin(identity: identity.trimmingCharacters(in: .whitespacesAndNewlines), password: remember ? password : nil, session: newSession), key: "login")
            session = newSession; demo = false; selectedDate = Date(); status = "已登录"
        } catch { if token == generation { self.error = error.localizedDescription } }
        guard token == generation else { return }
        busy = false
        if session != nil { await refresh() }
    }
    private func reauthenticate() async throws -> SchoolSession {
        let token = generation
        let service = api
        guard let login = try SecureStore.read("login", as: SavedLogin.self) else { throw QingxinError.loginRejected }
        let newSession: SchoolSession
        do { newSession = try await service.resume(identity: login.session.studentNo) }
        catch {
            guard token == generation else { throw CancellationError() }
            guard let password = login.password else { throw QingxinError.loginRejected }
            newSession = try await service.login(identity: login.identity, password: password)
        }
        guard token == generation, session != nil else { throw CancellationError() }
        try SecureStore.save(SavedLogin(identity: login.identity, password: login.password, session: newSession), key: "login")
        return newSession
    }
    func refresh() async {
        if demo { loadDemo(); return }
        guard let savedSession = session else { return }
        let dateKey = SchoolDate.key(selectedDate), token = generation
        let service = api
        refreshSequence += 1; let sequence = refreshSequence
        busy = true; error = nil
        defer { if sequence == refreshSequence { busy = false } }
        courses = []; lastUpdated = nil
        if let cache = SnapshotStore.read(identity: savedSession.studentNo, day: dateKey) {
            courses = cache.courses; lastUpdated = cache.updatedAt; status = "缓存课表 · 正在更新"
            if dateKey == SchoolDate.key(Date()) { todayList = cache.courses; todayUpdated = cache.updatedAt; todayStatus = status }
        }
        do {
            let query: CourseQuery
            do { query = try await service.courses(session: savedSession, dateKey: dateKey) }
            catch let error as QingxinError where error.requiresReauthentication {
                guard token == generation else { return }
                let newSession = try await reauthenticate()
                guard token == generation else { return }
                session = newSession
                query = try await service.courses(session: newSession, dateKey: dateKey)
            }
            guard token == generation, sequence == refreshSequence, dateKey == SchoolDate.key(selectedDate) else { return }
            courses = query.courses.sorted { ($0.begin ?? .distantFuture) < ($1.begin ?? .distantFuture) }
            for index in courses.indices where confirmedSignatures.contains(courses[index].id) { courses[index].signed = true }
            lastUpdated = Date()
            status = query.fromWeeklyFallback ? "当天无课 · 显示本周课表" : (courses.isEmpty ? "当天暂无课程" : "课表已更新")
            if dateKey == SchoolDate.key(Date()) { todayList = courses; todayUpdated = lastUpdated; todayStatus = status }
            try SnapshotStore.save(WidgetSnapshot(day: dateKey, updatedAt: Date(), courses: courses, isDemo: false), identity: savedSession.studentNo)
            if dateKey == SchoolDate.key(Date()) { await ReminderService.schedule(todayCourses, enabled: reminderEnabled) }
        } catch {
            guard token == generation, sequence == refreshSequence else { return }
            if error is CancellationError { return }
            status = lastUpdated == nil ? "课表更新失败" : "离线缓存 · 数据可能已变化"
            if dateKey == SchoolDate.key(Date()) { todayStatus = status }
            self.error = error.localizedDescription
        }
    }
    func schoolClock() async throws -> (timestamp: Int64, refreshAfter: TimeInterval) {
        if let value = sample?.reading(at: ProcessInfo.processInfo.systemUptime) { return value }
        let sent = ProcessInfo.processInfo.systemUptime
        let token = generation
        let timestamp = try await api.serverTimestamp()
        guard token == generation else { throw CancellationError() }
        let received = ProcessInfo.processInfo.systemUptime
        let fresh = try ClockSample(serverMs: timestamp, sentAtUptime: sent, receivedAtUptime: received)
        sample = fresh
        guard let value = fresh.reading(at: received) else { throw QingxinError.invalidResponse("校时已过期") }
        return value
    }
    func qr(code: SignCode) async throws -> (URL, TimeInterval) {
        let token = generation
        let reading = try await schoolClock()
        guard token == generation, !demo, session != nil else { throw CancellationError() }
        return (try api.qrURL(code: code, timestamp: reading.timestamp), reading.refreshAfter)
    }
    func sign(code: SignCode, course: Course? = nil) async {
        guard !demo, !signing, let savedSession = session else { return }
        let token = generation
        signing = true; error = nil; result = nil
        defer { if token == generation { signing = false } }
        let service = api
        do {
            let clock = try await schoolClock()
            guard token == generation else { return }
            var response: SignResult
            do { response = try await service.sign(session: savedSession, code: code, timestamp: clock.timestamp) }
            catch let error as QingxinError where error.requiresReauthentication {
                guard token == generation else { return }
                let fresh = try await reauthenticate()
                guard token == generation else { return }
                session = fresh
                let newClock = try await schoolClock()
                guard token == generation else { return }
                response = try await service.sign(session: fresh, code: code, timestamp: newClock.timestamp)
            }
            guard token == generation else { return }
            result = response
            if response.outcome == .signed, let course {
                confirmedSignatures.insert(course.id)
                if let index = courses.firstIndex(where: { $0.id == course.id }) { courses[index].signed = true }
                if let index = todayList.firstIndex(where: { $0.id == course.id }) { todayList[index].signed = true }
                try SnapshotStore.save(WidgetSnapshot(day: SchoolDate.key(Date()), updatedAt: Date(), courses: todayList, isDemo: false), identity: savedSession.studentNo)
            }
            await refresh()
        } catch {
            if token == generation, !(error is CancellationError) { self.error = error.localizedDescription }
        }
    }
    func foregroundTick() async {
        let day = SchoolDate.key(Date())
        if day != foregroundDay {
            foregroundDay = day; todayExcluded = []; autoPlans = [:]; autoAttempts = [:]; confirmedSignatures = []
            selectedDate = Date(); sample = nil; courses = []; todayList = []; todayUpdated = nil; lastUpdated = nil
            await refresh()
        }
        guard autoForeground, !demo, session != nil, !signing, !busy else { return }
        for course in todayCourses where !course.signed && !isExcluded(course) {
            guard let begin = course.begin, let code = course.signCode else { continue }
            let now = Date(), cutoff = begin.addingTimeInterval(20 * 60)
            guard now >= begin.addingTimeInterval(-15 * 60), now < cutoff,
                  (autoAttempts[course.id] ?? 0) < 3 else { continue }
            if autoPlans[course.id] == nil {
                let earliest = max(now, begin.addingTimeInterval(-15 * 60))
                let latest = max(earliest, begin)
                autoPlans[course.id] = earliest.addingTimeInterval(Double.random(in: 0...latest.timeIntervalSince(earliest)))
            }
            guard let due = autoPlans[course.id], now >= due else { continue }
            autoAttempts[course.id, default: 0] += 1
            autoPlans[course.id] = now.addingTimeInterval(Double.random(in: 60...90))
            await sign(code: code, course: course)
            return
        }
    }
    func setReminders(_ enabled: Bool) async {
        do {
            if enabled, try await ReminderService.permission() == false {
                reminderEnabled = false; error = "通知权限未开启，请在系统设置中允许通知"; return
            }
            reminderEnabled = enabled; UserDefaults.standard.set(enabled, forKey: "reminderEnabled")
            await ReminderService.schedule(todayCourses, enabled: enabled && !demo)
        } catch { self.error = error.localizedDescription }
    }
    func setAuto(_ enabled: Bool) {
        autoForeground = enabled; UserDefaults.standard.set(enabled, forKey: "autoForeground")
        autoPlans = [:]; autoAttempts = [:]
    }
    func logout() {
        generation += 1; refreshSequence += 1
        SecureStore.remove("login"); session = nil; demo = false; courses = []; sample = nil
        lastUpdated = nil; result = nil; error = nil; busy = false; signing = false; autoPlans = [:]; autoAttempts = [:]; todayExcluded = []; confirmedSignatures = []
        status = "登录后查看今日课程"; todayStatus = status; todayList = []; todayUpdated = nil
        SnapshotStore.clear(); ReminderService.clear()
    }
    func enterDemo() {
        generation += 1; refreshSequence += 1; busy = false; signing = false; error = nil
        demo = true; selectedDate = Date(); lastUpdated = nil; result = nil; loadDemo()
    }
    private func loadDemo() {
        let day = SchoolDate.key(selectedDate)
        courses = [
            Course(scheduleId: "1000001", name: "高等数学", teacher: "示例教师", beginTime: "08:30", endTime: "10:10", day: day, signed: true),
            Course(scheduleId: "1000002", name: "学术英语", teacher: "示例教师", beginTime: "14:00", endTime: "15:40", day: day),
            Course(scheduleId: "1000003", name: "科学前沿讲座", teacher: "示例教师", beginTime: "18:30", endTime: "20:00", day: day)
        ]
        status = "演示模式 · 示例课表不连接学校"; lastUpdated = Date()
        if day == SchoolDate.key(Date()) { todayList = courses; todayStatus = status; todayUpdated = lastUpdated }
    }
    func exitDemo() {
        demo = false; courses = []; selectedDate = Date(); lastUpdated = nil; result = nil
        todayList = []; todayUpdated = nil
        status = session == nil ? "登录后查看今日课程" : "请刷新课表"
        todayStatus = status
    }
    func backgroundRefresh() async {
        guard !demo, let savedSession = session else { return }
        let token = generation, day = SchoolDate.key(Date())
        do {
            let query = try await api.courses(session: savedSession, dateKey: day)
            guard token == generation, !Task.isCancelled else { return }
            todayList = query.courses.sorted { ($0.begin ?? .distantFuture) < ($1.begin ?? .distantFuture) }
            for index in todayList.indices where confirmedSignatures.contains(todayList[index].id) { todayList[index].signed = true }
            let snapshot = WidgetSnapshot(day: day, updatedAt: Date(), courses: todayList, isDemo: false)
            try SnapshotStore.save(snapshot, identity: savedSession.studentNo)
            todayUpdated = snapshot.updatedAt; todayStatus = "今日课表已更新"
            await ReminderService.schedule(todayCourses, enabled: reminderEnabled)
        } catch { /* Background refresh is best effort; foreground refresh presents errors. */ }
    }
}
