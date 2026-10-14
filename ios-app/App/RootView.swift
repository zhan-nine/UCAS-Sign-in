// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import QingxinCore

struct RootView: View {
    @EnvironmentObject private var state: AppState
    var body: some View {
        TabView {
            NavigationStack { TodayView() }.tabItem { Label("今日", systemImage: "sun.max") }
            NavigationStack { ScheduleView() }.tabItem { Label("课表", systemImage: "calendar") }
            NavigationStack { ManualView() }.tabItem { Label("打卡", systemImage: "checkmark.circle") }
            NavigationStack { LectureView() }.tabItem { Label("讲座", systemImage: "person.3") }
            NavigationStack { SettingsView() }.tabItem { Label("设置", systemImage: "slider.horizontal.3") }
        }
        .alert("操作提示", isPresented: Binding(get: { state.error != nil }, set: { if !$0 { state.error = nil } })) {
            Button("知道了", role: .cancel) { state.error = nil }
        } message: { Text(state.error ?? "") }
    }
}

struct TodayView: View {
    @EnvironmentObject private var state: AppState
    @State private var qrCourse: Course?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Label("UCAS · 轻新课堂", systemImage: "leaf.fill").font(.caption.weight(.semibold)).foregroundStyle(Color.qingxin)
                    Spacer()
                    if state.busy { ProgressView().controlSize(.small) }
                }
                if !state.loggedIn { LoginView() }
                else {
                    if state.demo { Label("演示模式 · 签到功能已禁用", systemImage: "play.rectangle").font(.footnote).foregroundStyle(.secondary) }
                    hero
                    ResultBanner()
                    HStack {
                        Text("今日课程").font(.title3.bold())
                        Spacer()
                        Text("\(state.todayCourses.count) 节").font(.subheadline).foregroundStyle(.secondary)
                    }
                    if state.todayCourses.isEmpty {
                        ContentUnavailableView("今天暂无课程", systemImage: "cup.and.saucer", description: Text(state.todayStatus))
                    }
                    ForEach(state.todayCourses) { course in
                        CourseCard(course: course, qrAction: { qrCourse = course })
                    }
                    Text(state.todayStatus).font(.caption).foregroundStyle(.secondary)
                    if let updated = state.todayUpdated {
                        Text("更新于 \(updated.formatted(date: .omitted, time: .shortened))").font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }.padding(20)
        }
        .background(Color.qingxinBackground)
        .navigationTitle("清新签到")
        .task {
            if SchoolDate.key(state.selectedDate) != SchoolDate.key(Date()) { state.selectedDate = Date(); await state.refresh() }
        }
        .refreshable { state.selectedDate = Date(); await state.refresh() }
        .sheet(item: $qrCourse) { course in NavigationStack { QRView(course: course) } }
    }
    private var hero: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(Date().formatted(.dateTime.month().day().weekday().locale(Locale(identifier: "zh_CN"))))
                .font(.subheadline).opacity(0.85)
            if let course = state.currentCourse {
                Text(course.isInWindow(at: Date()) ? "正在进行" : "下一节课").font(.caption.weight(.medium)).opacity(0.8)
                Text(course.name).font(.title2.bold())
                Text("\(SchoolDate.time(course.begin)) — \(SchoolDate.time(course.end))").font(.title3.monospacedDigit())
                HStack {
                    Label(course.status(at: Date()), systemImage: course.signed ? "checkmark.seal.fill" : "clock")
                    Spacer()
                    if let code = course.signCode {
                        Button { Task { await state.sign(code: code, course: course) } } label: {
                            Text(state.signing ? "提交中…" : "一键签到").font(.subheadline.bold()).padding(.horizontal, 16).padding(.vertical, 10)
                        }
                        .background(.white, in: Capsule()).foregroundStyle(Color.qingxin)
                        .disabled(state.demo || state.signing || course.signed)
                    }
                }.font(.footnote)
            } else {
                Text("今天的课程已结束").font(.title2.bold())
                Text("好好休息，明天继续。").font(.subheadline).opacity(0.85)
            }
        }
        .padding(22).frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(.white)
        .background(LinearGradient(colors: [.qingxin, Color(red: 0.18, green: 0.61, blue: 0.49)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 24))
    }
}

struct LoginView: View {
    @EnvironmentObject private var state: AppState
    @State private var identity = ""
    @State private var password = ""
    @State private var remember = false
    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Image(systemName: "leaf.circle.fill").font(.system(size: 60)).foregroundStyle(Color.qingxin)
            Text("每一堂课，\n从容开始。").font(.largeTitle.bold())
            Text("使用 SEP 邮箱或轻新课堂学号登录。首次使用请在设置中填写学校接口配置。").foregroundStyle(.secondary)
            VStack(spacing: 16) {
                TextField("SEP 邮箱 / 学号", text: $identity).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                Divider()
                SecureField("密码", text: $password).textContentType(.password)
                Toggle("保存密码用于会话恢复", isOn: $remember).font(.subheadline)
            }.padding(20).background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
            Button {
                Task { await state.login(identity: identity, password: password, remember: remember); password = "" }
            } label: {
                HStack { Spacer(); if state.busy { ProgressView().tint(.white) }; Text("登录"); Spacer() }.padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent).disabled(identity.isEmpty || password.isEmpty || state.busy)
            Button("先看看演示") { state.enterDemo() }.frame(maxWidth: .infinity)
            Text("账号、密码和会话仅保存在本机 Keychain。登录请求直接发送到你配置的学校服务。").font(.caption).foregroundStyle(.secondary)
        }.padding(.vertical, 24)
    }
}

struct CourseCard: View {
    @EnvironmentObject private var state: AppState
    let course: Course
    var qrAction: (() -> Void)? = nil
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            RoundedRectangle(cornerRadius: 3).fill(course.signed ? Color.qingxin.opacity(0.35) : Color.qingxin).frame(width: 4)
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top) {
                    Text(course.name).font(.headline)
                    Spacer()
                    Text(course.status(at: Date())).font(.caption.weight(.semibold)).foregroundStyle(course.signed ? Color.qingxin : .secondary)
                }
                Label("\(SchoolDate.time(course.begin)) — \(SchoolDate.time(course.end))", systemImage: "clock").font(.subheadline).foregroundStyle(.secondary)
                if !course.teacher.isEmpty { Text(course.teacher).font(.caption).foregroundStyle(.secondary) }
                if state.isExcluded(course) { Text("自动签到已跳过").font(.caption).foregroundStyle(.orange) }
                if course.day == SchoolDate.key(Date()), let qrAction, let code = course.signCode {
                    HStack {
                        Button("查看二维码", systemImage: "qrcode", action: qrAction).disabled(state.demo)
                        Spacer()
                        Button("签到") { Task { await state.sign(code: code, course: course) } }
                            .disabled(state.demo || course.signed || state.signing)
                    }.font(.subheadline)
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true).padding(18)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
        .contextMenu {
            Button("今日跳过 / 恢复") { state.exclude(course, permanently: false) }
            Button("长期跳过 / 恢复") { state.exclude(course, permanently: true) }
        }
    }
}

struct ResultBanner: View {
    @EnvironmentObject private var state: AppState
    var body: some View {
        if let result = state.result {
            Label(result.message, systemImage: result.outcome == .signed ? "checkmark.circle.fill" : "info.circle")
                .font(.subheadline).foregroundStyle(result.outcome == .signed ? Color.qingxin : Color.orange)
                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
        }
    }
}

struct ScheduleView: View {
    @EnvironmentObject private var state: AppState
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HStack {
                    Button { moveDay(-1) } label: { Image(systemName: "chevron.left").padding(10) }
                        .accessibilityLabel("上一天")
                    DatePicker("日期", selection: $state.selectedDate, displayedComponents: .date).labelsHidden().environment(\.timeZone, SchoolDate.zone)
                    Button { moveDay(1) } label: { Image(systemName: "chevron.right").padding(10) }
                        .accessibilityLabel("下一天")
                }
                Text(state.status).font(.caption).foregroundStyle(.secondary)
                if !state.loggedIn { ContentUnavailableView("请先登录", systemImage: "person.crop.circle") }
                else if state.courses.isEmpty { ContentUnavailableView("当天暂无课程", systemImage: "calendar") }
                ForEach(state.courses) { course in
                    VStack(alignment: .leading, spacing: 6) {
                        if course.day != SchoolDate.key(state.selectedDate) { Text(course.day).font(.caption).foregroundStyle(.secondary) }
                        CourseCard(course: course)
                    }
                }
            }.padding(20)
        }
        .navigationTitle("课表").background(Color.qingxinBackground)
        .toolbar { Button("今天") { state.selectedDate = Date() } }
        .task(id: SchoolDate.key(state.selectedDate)) { await state.refresh() }
        .refreshable { await state.refresh() }
    }
    private func moveDay(_ amount: Int) {
        state.selectedDate = SchoolDate.calendar.date(byAdding: .day, value: amount, to: state.selectedDate) ?? state.selectedDate
    }
}

struct ManualView: View {
    @EnvironmentObject private var state: AppState
    @State private var input = ""
    @FocusState private var inputFocused: Bool
    var body: some View {
        Form {
            Section {
                TextField("7 位编号 / 32 位 UUID", text: $input)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().focused($inputFocused)
                    .submitLabel(.done).onSubmit { inputFocused = false }
                if let code = SignCode(input) { Label(code.label, systemImage: "checkmark.circle").foregroundStyle(Color.qingxin) }
                else if !input.isEmpty { Text("编号格式不正确").foregroundStyle(.orange) }
            } header: { Text("现场课程编号") } footer: { Text("输入现场读取的编号。支持带连字符的 UUID；提交时使用学校服务器时间。") }
            Section {
                Button {
                    if let code = SignCode(input) { Task { await state.sign(code: code) } }
                } label: { HStack { Text(state.signing ? "提交中…" : "提交签到"); Spacer(); if state.signing { ProgressView() } } }
                .disabled(!state.loggedIn || state.demo || state.signing || SignCode(input) == nil)
                ResultBanner()
            }
            if !state.loggedIn || state.demo { Section { Text(state.demo ? "演示模式不会提交签到。" : "请先在今日页面登录。") } }
        }.navigationTitle("手动打卡")
            .toolbar {
                if inputFocused {
                    ToolbarItem(placement: .topBarTrailing) { Button("完成") { inputFocused = false } }
                }
            }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var state: AppState
    @State private var editing = false
    var body: some View {
        Form {
            Section("学校服务") {
                Button("接口配置", systemImage: "network") { editing = true }
                Text(state.configuration.apiBaseURL.isEmpty ? "尚未配置" : "已配置本机接口地址").font(.caption).foregroundStyle(.secondary)
            }
            Section {
                Toggle("上课前 15 分钟提醒", isOn: Binding(get: { state.reminderEnabled }, set: { enabled in Task { await state.setReminders(enabled) } }))
                Toggle("前台自动签到", isOn: Binding(get: { state.autoForeground }, set: { state.setAuto($0) }))
            } header: { Text("提醒与签到") } footer: {
                Text("自动签到仅在 App 保持前台时执行，后台或锁屏后会暂停。iOS 的后台刷新由系统安排，不能保证准时签到。长按课程卡片可设置跳过名单。")
            }
            Section("账号") {
                if state.demo { Button("退出演示") { state.exitDemo(); Task { await state.refresh() } } }
                if let session = state.session { Text("学号：\(session.studentNo)"); Button("退出登录并清除本机数据", role: .destructive) { state.logout() } }
                else if !state.demo { Text("未登录").foregroundStyle(.secondary) }
            }
            Section("关于") {
                LabeledContent("iOS 版本", value: "0.1.0")
                Link("查看移植源码", destination: URL(string: "https://github.com/Vel044/UCAS-Sign-in/tree/ios-port")!)
                Link("原项目 · zhan-nine", destination: URL(string: "https://github.com/zhan-nine/UCAS-Sign-in")!)
                Text("© 2026 UCAS-Sign-in contributors\n本移植遵循 AGPL-3.0；软件按现状提供，不附带担保。可按该许可证使用、修改和分发。")
                    .font(.caption).foregroundStyle(.secondary)
                NavigationLink("开源许可证") { LicenseView() }
            }
        }.navigationTitle("设置")
        .sheet(isPresented: $editing) { ConfigurationView() }
    }
}

struct ConfigurationView: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var value = EndpointConfiguration()
    var body: some View {
        NavigationStack {
            Form {
                Section("学校接口") {
                    TextField("API 根地址", text: $value.apiBaseURL).keyboardType(.URL)
                    TextField("verificationUrl 模板", text: $value.verificationURL, axis: .vertical)
                }
                Section("讲座预约（可选）") { TextField("SEP / 讲座系统登录地址", text: $value.lectureURL).keyboardType(.URL) }
                Section {
                    Text("配置来自原项目的 local.properties，校内主机和端口未公开。保留 verificationUrl 中的原有占位符，App 会原样提交。配置仅存于本机安全存储。")
                    Text("建议使用 HTTPS。HTTP 地址会以明文传输登录请求，仅在学校服务确实要求时使用。")
                }.font(.footnote).foregroundStyle(.secondary)
            }
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .navigationTitle("接口配置").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { state.error = nil; state.saveConfiguration(value); if state.error == nil { dismiss() } }
                }
            }
            .onAppear { value = state.configuration }
            .alert("配置提示", isPresented: Binding(get: { state.error != nil }, set: { if !$0 { state.error = nil } })) {
                Button("知道了") { state.error = nil }
            } message: { Text(state.error ?? "") }
        }
    }
}

struct LicenseView: View {
    private var license: String {
        guard let path = Bundle.main.url(forResource: "AGPL-3.0", withExtension: "txt"), let text = try? String(contentsOf: path) else { return "请从源码仓库中的 LICENSE 查阅完整许可证。" }
        return text
    }
    var body: some View { ScrollView { Text(license).font(.caption.monospaced()).textSelection(.enabled).padding() }.navigationTitle("AGPL-3.0") }
}
