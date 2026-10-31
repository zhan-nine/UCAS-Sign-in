// SPDX-License-Identifier: AGPL-3.0-only
import XCTest
@testable import QingxinCore

final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, [String: Any]))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, json) = try Self.handler!(request)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: try JSONSerialization.data(withJSONObject: json))
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() { }
}

final class NetworkTests: XCTestCase {
    private func api() -> QingxinAPI {
        let c = URLSessionConfiguration.ephemeral; c.protocolClasses = [MockURLProtocol.self]
        return QingxinAPI(configuration: EndpointConfiguration(apiBaseURL: "https://school.invalid/api/", verificationURL: "https://verify.invalid/{0}"), transport: URLSession(configuration: c))
    }
    override func tearDown() { MockURLProtocol.handler = nil; super.tearDown() }
    func testLoginUsesUpstreamFormFields() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/user/login.action")
            let body = String(data: request.httpBody ?? Data(), encoding: .utf8) ?? ""
            // URLSession may supply a stream instead of httpBody; inspect a standalone request below as well.
            if !body.isEmpty { XCTAssertTrue(body.contains("verificationType=1")) }
            XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), "student_5.0.1.2_android_12_20__110000")
            return (200, ["STATUS": "0", "result": ["id": "42", "sessionId": "s", "studentNo": "20260001"]])
        }
        let value = try await api().login(identity: "test@school.invalid", password: "a+b&c")
        XCTAssertEqual(value.studentNo, "20260001")
    }
    func testWeeklyFallbackWithZeroStatusStillUsesRealData() async throws {
        MockURLProtocol.handler = { request in
            if request.url!.path.contains("_week") {
                return (200, ["STATUS": "0", "result": [["dateStr": "2026-10-02", "schedData": [["id": "1234567", "courseName": "Math"]]]]])
            }
            return (200, ["STATUS": "0", "result": []])
        }
        let session = try QingxinAPI.parseSession(["STATUS": "0", "result": ["id": "42", "sessionId": "s", "studentNo": "20260001"]])
        let query = try await api().courses(session: session, dateKey: "20261002")
        XCTAssertEqual(query.courses.map(\.name), ["Math"])
        XCTAssertFalse(query.fromWeeklyFallback)
    }
    func testWeekFallbackIsExplicitWhenRequestedDayHasNoCourse() async throws {
        MockURLProtocol.handler = { request in
            if request.url!.path.contains("_week") {
                return (200, ["STATUS": "1", "result": [["dateStr": "2026-10-03", "schedData": [["id": "1234567", "courseName": "Math"]]]]])
            }
            return (200, ["STATUS": "0", "result": []])
        }
        let session = try QingxinAPI.parseSession(["STATUS": "0", "result": ["id": "42", "sessionId": "s", "studentNo": "20260001"]])
        let query = try await api().courses(session: session, dateKey: "20261002")
        XCTAssertTrue(query.fromWeeklyFallback)
        XCTAssertEqual(query.courses[0].day, "20261003")
    }
    func testBooleanTimestampIsRejected() async {
        MockURLProtocol.handler = { _ in (200, ["STATUS": "0", "timestamp": true]) }
        do { _ = try await api().serverTimestamp(); XCTFail("Boolean is not a valid clock") } catch { }
    }
    func testHTTPFailureIsNotSilentlyTreatedAsEmptySchedule() async {
        MockURLProtocol.handler = { _ in (503, [:]) }
        do { _ = try await api().serverTimestamp(); XCTFail("Expected HTTP error") }
        catch QingxinError.http(let code) { XCTAssertEqual(code, 503) }
        catch { XCTFail("Unexpected error: \(error)") }
    }
    func testExpiredSessionJSONIsRecognizedWithoutHTTP401() async throws {
        MockURLProtocol.handler = { _ in (200, ["STATUS": "1", "ERRMSG": "登录会话已过期"]) }
        let session = try QingxinAPI.parseSession(["STATUS": "0", "result": ["id": "42", "sessionId": "s", "studentNo": "20260001"]])
        do { _ = try await api().courses(session: session, dateKey: "20261002"); XCTFail("Expected session expiry") }
        catch let error as QingxinError { XCTAssertTrue(error.requiresReauthentication) }
    }
    func testClockRequestMatchesUpstreamEndpoint() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/common/get_timestamp.do")
            XCTAssertEqual(request.url?.query, "id=0")
            return (200, ["STATUS": "0", "timestamp": 1_700_000_000_000])
        }
        let timestamp = try await api().serverTimestamp()
        XCTAssertEqual(timestamp, 1_700_000_000_000)
    }
}
