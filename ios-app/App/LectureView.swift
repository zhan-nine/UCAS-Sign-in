// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import WebKit

struct LectureView: View {
    @EnvironmentObject private var state: AppState
    private var url: URL? {
        guard let url = URL(string: state.configuration.lectureURL), ["https", "http"].contains(url.scheme), url.host != nil else { return nil }
        return url
    }
    var body: some View {
        Group {
            if let url, !state.demo { LectureBrowser(url: url) }
            else {
                ContentUnavailableView("讲座预约", systemImage: "person.3.sequence", description: Text("在设置中填写 SEP / 讲座系统登录地址。此页面通过学校网页登录，不读取或保存网页密码。"))
            }
        }.navigationTitle("讲座预约").navigationBarTitleDisplayMode(.inline)
    }
}

struct LectureBrowser: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // Ephemeral site data: the lecture login is separate from API credentials.
        configuration.websiteDataStore = .nonPersistent()
        let web = WKWebView(frame: .zero, configuration: configuration)
        web.allowsBackForwardNavigationGestures = true; web.load(URLRequest(url: url)); return web
    }
    func updateUIView(_ view: WKWebView, context: Context) { }
}
