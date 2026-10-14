// SPDX-License-Identifier: AGPL-3.0-only
import XCTest
import QingxinCore
@testable import Qingxin

final class AppStateTests: XCTestCase {
    @MainActor func testBrowsingAnotherDayPreservesTodayState() async throws {
        let state = AppState()
        state.enterDemo()
        let original = state.todayCourses
        XCTAssertEqual(original.count, 3)
        state.selectedDate = try XCTUnwrap(SchoolDate.calendar.date(byAdding: .day, value: 1, to: Date()))
        await state.refresh()
        XCTAssertEqual(state.todayCourses, original)
        XCTAssertTrue(state.courses.allSatisfy { $0.day == SchoolDate.key(state.selectedDate) })
        XCTAssertTrue(state.todayCourses.allSatisfy { $0.day == SchoolDate.key(Date()) })
    }

    @MainActor func testLogoutClearsTodayAndBrowseSnapshots() {
        let state = AppState()
        state.enterDemo()
        XCTAssertFalse(state.todayCourses.isEmpty)
        state.logout()
        XCTAssertFalse(state.loggedIn)
        XCTAssertTrue(state.todayCourses.isEmpty)
        XCTAssertTrue(state.courses.isEmpty)
        XCTAssertNil(state.todayUpdated)
    }
}
