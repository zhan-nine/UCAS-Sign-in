// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import BackgroundTasks
import QingxinCore

@main struct QingxinApp: App {
    @StateObject private var state = AppState()
    @Environment(\.scenePhase) private var scenePhase
    static let backgroundID = "com.vel.qingxin.refresh"

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(state).tint(.qingxin)
                .task { if state.loggedIn { await state.refresh() } }
                .onOpenURL { url in
                    if url.scheme == "qingxin" { state.selectedDate = Date(); Task { await state.refresh() } }
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background { Self.scheduleRefresh() }
                    if phase == .active, state.loggedIn {
                        Task {
                            if SchoolDate.key(state.selectedDate) == SchoolDate.key(Date()) { await state.refresh() }
                            else { await state.backgroundRefresh() }
                        }
                    }
                }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { return }
                    while !Task.isCancelled {
                        await state.foregroundTick()
                        do { try await Task.sleep(for: .seconds(10)) } catch { break }
                    }
                }
        }
        .backgroundTask(.appRefresh(Self.backgroundID)) {
            await state.backgroundRefresh()
            await Self.scheduleRefresh()
        }
    }
    static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: backgroundID)
        request.earliestBeginDate = Date().addingTimeInterval(60 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}

extension Color {
    static let qingxin = Color(red: 0.09, green: 0.49, blue: 0.39)
    static let qingxinBackground = Color(uiColor: .systemGroupedBackground)
}
