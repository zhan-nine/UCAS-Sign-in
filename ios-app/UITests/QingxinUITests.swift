// SPDX-License-Identifier: AGPL-3.0-only
import XCTest

final class QingxinUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }
    private func demoApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()
        return app
    }
    private func screenshot(_ app: XCUIApplication, name: String) {
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = name; image.lifetime = .keepAlways; add(image)
    }

    func testDemoShowsCoursesAndNeverEnablesAttendanceSubmission() {
        let app = demoApp()
        XCTAssertTrue(app.staticTexts["演示模式 · 签到功能已禁用"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["高等数学"].exists)
        screenshot(app, name: "today-demo")
        app.tabBars.buttons["打卡"].tap()
        let input = app.textFields["7 位编号 / 32 位 UUID"]
        let textView = app.textViews.firstMatch
        if input.exists { input.tap(); input.typeText("1234567") }
        else { textView.tap(); textView.typeText("1234567") }
        XCTAssertTrue(app.staticTexts["7 位节次编号"].exists)
        XCTAssertFalse(app.buttons["提交签到"].isEnabled)
        app.navigationBars.buttons["完成"].tap()
        screenshot(app, name: "manual-demo")
    }

    func testMalformedManualCodeIsExplainedBeforeSubmission() {
        let app = demoApp()
        app.tabBars.buttons["打卡"].tap()
        let input = app.textFields["7 位编号 / 32 位 UUID"]
        let textView = app.textViews.firstMatch
        if input.exists { input.tap(); input.typeText("123456") }
        else { textView.tap(); textView.typeText("123456") }
        XCTAssertTrue(app.staticTexts["编号格式不正确"].exists)
        XCTAssertFalse(app.buttons["提交签到"].isEnabled)
    }

    func testScheduleAndLecturePagesLoadInDemo() {
        let app = demoApp()
        app.tabBars.buttons["课表"].tap()
        XCTAssertTrue(app.staticTexts["高等数学"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["今天"].exists)
        app.buttons["下一天"].tap()
        XCTAssertTrue(app.staticTexts["高等数学"].exists)
        screenshot(app, name: "schedule-demo")
        app.tabBars.buttons["讲座"].tap()
        XCTAssertTrue(app.staticTexts["讲座预约"].firstMatch.waitForExistence(timeout: 5))
    }

    func testExitDemoReturnsToLoginAndKeepsSigningUnavailable() {
        let app = demoApp()
        app.tabBars.buttons["设置"].tap()
        XCTAssertTrue(app.switches["前台自动签到"].waitForExistence(timeout: 5))
        screenshot(app, name: "settings-demo")
        app.buttons["退出演示"].tap()
        app.tabBars.buttons["今日"].tap()
        XCTAssertTrue(app.textFields["SEP 邮箱 / 学号"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["登录"].isEnabled)
    }
}
