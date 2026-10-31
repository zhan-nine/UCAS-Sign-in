// SPDX-License-Identifier: AGPL-3.0-only
import XCTest
@testable import QingxinCore

final class ProtocolTests: XCTestCase {
    private let config = EndpointConfiguration(apiBaseURL: "https://school.invalid/api/", verificationURL: "https://verify.invalid/check?user={0}&password={1}&type={2}")

    func testSevenDigitCode() {
        XCTAssertEqual(SignCode(" 1234567\n")?.parameter, "courseSchedId")
        XCTAssertEqual(SignCode(" 1234567\n")?.value, "1234567")
    }
    func testUUIDNormalizationAndFallback() {
        let code = SignCode("01234567-89ab-cdef-0123-456789abcdef")
        XCTAssertEqual(code?.parameter, "timeTableId")
        XCTAssertEqual(code?.value, "0123456789ABCDEF0123456789ABCDEF")
        let course = Course(scheduleId: "", uuid: "01234567-89ab-cdef-0123-456789abcdef", name: "Test", beginTime: "08:00", endTime: "09:00", day: "20261002")
        XCTAssertEqual(course.signCode, code)
    }
    func testInvalidCodesNeverBecomeRequests() {
        for text in ["", "   ", "123456", "12345678", "123456a", "１２３４５６７", "123-4567", "0123456789abcdef0123456789abcdeg", "0123456789abcdef0123456789abcde"] {
            XCTAssertNil(SignCode(text), text)
        }
    }
    func testFormEncodingPreservesReservedPasswordCharacters() {
        XCTAssertEqual(QingxinAPI.formBody([("password", "a+b&c= d%中文")]), "password=a%2Bb%26c%3D%20d%25%E4%B8%AD%E6%96%87")
    }
    func testOnlyHTTPAndHTTPSBasesWithoutCredentialsAreAllowed() throws {
        for text in ["", "file:///tmp/test", "https://user:password@school.invalid", "https://school.invalid?x=1", "https://school.invalid/#fragment"] {
            XCTAssertThrowsError(try EndpointConfiguration(apiBaseURL: text).validatedBaseURL())
        }
        XCTAssertEqual(try EndpointConfiguration(apiBaseURL: "https://school.invalid/api").validatedBaseURL().absoluteString, "https://school.invalid/api/")
    }
    func testSignRequestIncludesExactIdentifierAndSession() throws {
        let session = try QingxinAPI.parseSession(["STATUS": "0", "result": ["id": 42, "sessionId": "test-session", "studentNo": "20260001"]])
        let api = QingxinAPI(configuration: config)
        let request = try api.request(path: "course/stu_scan_sign.action", session: session, query: [URLQueryItem(name: "courseSchedId", value: "1234567"), URLQueryItem(name: "timestamp", value: "1700000000000"), URLQueryItem(name: "id", value: session.userId)])
        XCTAssertEqual(request.value(forHTTPHeaderField: "sessionId"), "test-session")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Cache-Control"), "no-store")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/course/stu_scan_sign.action")
        XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.count, 3)
    }
    func testQRDoesNotContainUserOrSession() throws {
        let url = try QingxinAPI(configuration: config).qrURL(code: SignCode("01234567-89ab-cdef-0123-456789abcdef")!, timestamp: 1_700_000_000_000)
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(Set(items.map(\.name)), ["timeTableId", "timestamp"])
        XCTAssertEqual(items.first?.value, "0123456789ABCDEF0123456789ABCDEF")
    }
    func testMissingSessionIdentityIsRejected() {
        for result: [String: Any] in [["id": "42", "sessionId": "s"], ["id": NSNull(), "sessionId": "s", "studentNo": "1"], ["id": "42", "sessionId": "", "studentNo": "1"]] {
            XCTAssertThrowsError(try QingxinAPI.parseSession(["STATUS": "0", "result": result]))
        }
        XCTAssertThrowsError(try QingxinAPI.parseSession(["STATUS": "1", "result": ["id": "42", "sessionId": "s", "studentNo": "1"]]))
    }
    func testNullCourseFieldsStayEmpty() {
        let rows = QingxinAPI.parseCourses([["id": "1234567", "courseName": "Math", "courseId": NSNull(), "signStatus": 1]], day: "2026-10-02")
        XCTAssertEqual(rows[0].courseId, "")
        XCTAssertEqual(rows[0].teacher, "")
        XCTAssertEqual(rows[0].day, "20261002")
        XCTAssertTrue(rows[0].signed)
    }
    func testSignRequiresExplicitServerConfirmation() {
        XCTAssertEqual(QingxinAPI.parseSign(["STATUS": "0", "result": ["stuSignStatus": "1"]]).outcome, .signed)
        for value: [String: Any] in [
            ["STATUS": "0"], ["STATUS": "0", "success": false, "result": ["stuSignStatus": "1"]],
            ["STATUS": "0", "ERRCODE": "42", "result": ["stuSignStatus": "1"]],
            ["STATUS": "1", "result": ["stuSignStatus": "1"]], ["STATUS": "0", "result": ["stuSignStatus": "0"]]
        ] { XCTAssertNotEqual(QingxinAPI.parseSign(value).outcome, .signed) }
    }
    func testExpiredCodeAndAlreadySignedAreDistinct() {
        XCTAssertEqual(QingxinAPI.parseSign(["STATUS": "1", "ERRMSG": "二维码已过期"]).outcome, .expired)
        let duplicate = QingxinAPI.parseSign(["STATUS": "1", "ERRMSG": "重复签到"])
        XCTAssertEqual(duplicate.outcome, .rejected)
        XCTAssertTrue(duplicate.message.contains("刷新课程状态"))
    }
}

final class TimeTests: XCTestCase {
    func testSchoolTimeUsesShanghaiEvenWhenDeviceTimezoneDiffers() throws {
        let date = try XCTUnwrap(SchoolDate.instant(day: "20261002", time: "8:00"))
        let iso = ISO8601DateFormatter()
        XCTAssertEqual(iso.string(from: date), "2026-10-02T00:00:00Z")
        XCTAssertEqual(SchoolDate.key(date), "20261002")
    }
    func testFlexibleTimeFormats() throws {
        let expected = try XCTUnwrap(SchoolDate.instant(day: "20261002", time: "08:00:00"))
        for raw in ["8:00", "08：00", "0800", "080000", "2026-10-02 08:00:00.123", "2026-10-02T08:00:00"] {
            XCTAssertEqual(SchoolDate.instant(day: "2026-10-02", time: raw), expected, raw)
        }
    }
    func testMalformedDatesDoNotFallBackToToday() {
        for day in ["", "bad", "20260230"] { XCTAssertNil(SchoolDate.instant(day: day, time: "08:00")) }
        for time in ["", "25:00", "08:60", "08:00:99", "08:00:xx"] { XCTAssertNil(SchoolDate.instant(day: "20261002", time: time)) }
    }
    func testExplicitTimezoneIsHonored() {
        XCTAssertEqual(SchoolDate.instant(day: "", time: "2026-10-02T00:00:00Z"), SchoolDate.instant(day: "20261002", time: "08:00"))
    }
    func testSignWindowHasInclusiveStartAndExclusiveEnd() throws {
        let course = Course(scheduleId: "1234567", name: "Math", beginTime: "08:00", endTime: "09:00", day: "20261002")
        let begin = try XCTUnwrap(course.begin), end = try XCTUnwrap(course.end)
        XCTAssertFalse(course.isInWindow(at: begin.addingTimeInterval(-1501)))
        XCTAssertTrue(course.isInWindow(at: begin.addingTimeInterval(-1500)))
        XCTAssertTrue(course.isInWindow(at: end.addingTimeInterval(-1)))
        XCTAssertFalse(course.isInWindow(at: end))
    }
    func testClockCompensatesRoundTripAndUsesMonotonicTime() throws {
        let sample = try ClockSample(serverMs: 1_700_000_000_000, sentAtUptime: 10, receivedAtUptime: 10.2)
        XCTAssertEqual(Double(try XCTUnwrap(sample.reading(at: 10.2)?.timestamp)), 1_700_000_000_100, accuracy: 1)
        XCTAssertEqual(Double(try XCTUnwrap(sample.reading(at: 12.2)?.timestamp)), 1_700_000_002_100, accuracy: 1)
        XCTAssertEqual(sample.reading(at: 37.2)?.refreshAfter ?? 0, 3, accuracy: 0.001)
        XCTAssertNil(sample.reading(at: 40.2))
        XCTAssertNil(sample.reading(at: 9))
    }
    func testInvalidClockSamplesFailClosed() {
        XCTAssertThrowsError(try ClockSample(serverMs: 42, sentAtUptime: 10, receivedAtUptime: 11))
        XCTAssertThrowsError(try ClockSample(serverMs: 1_700_000_000_000, sentAtUptime: 11, receivedAtUptime: 10))
    }
    func testWidgetNeverDisplaysYesterdaysCourses() throws {
        let course = Course(scheduleId: "1234567", name: "Math", beginTime: "08:00", endTime: "09:00", day: "20261002")
        let date = try XCTUnwrap(course.begin)
        let snapshot = WidgetSnapshot(day: "20261002", updatedAt: date, courses: [course], isDemo: false)
        XCTAssertEqual(snapshot.courses(at: date).count, 1)
        XCTAssertTrue(snapshot.courses(at: date.addingTimeInterval(24 * 3600)).isEmpty)
        let data = try JSONEncoder().encode(snapshot)
        XCTAssertEqual(try JSONDecoder().decode(WidgetSnapshot.self, from: data).courses, [course])
    }
}
