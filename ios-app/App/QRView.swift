// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import CoreImage.CIFilterBuiltins
import QingxinCore

struct QRView: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.scenePhase) private var phase
    @Environment(\.dismiss) private var dismiss
    let course: Course
    @State private var image: UIImage?
    @State private var expires = Date.distantPast
    @State private var message = "正在同步学校时间…"
    @State private var loading = false
    var body: some View {
        VStack(spacing: 24) {
            Text(course.name).font(.title2.bold()).multilineTextAlignment(.center)
            TimelineView(.periodic(from: .now, by: 0.25)) { context in
                VStack(spacing: 16) {
                    if phase == .active, context.date < expires, let image {
                        Image(uiImage: image).interpolation(.none).resizable().scaledToFit().padding(20)
                            .background(.white, in: RoundedRectangle(cornerRadius: 20))
                            .accessibilityLabel("学校课程签到二维码")
                        Text("\(max(0, expires.timeIntervalSince(context.date)), specifier: "%.1f") 秒后刷新")
                            .monospacedDigit().font(.caption).foregroundStyle(.secondary)
                    } else {
                        ContentUnavailableView(message, systemImage: "qrcode", description: Text("二维码必须使用有效的学校时间。"))
                    }
                }
            }.frame(maxWidth: 330, minHeight: 330)
            if !course.isInWindow(at: Date()) {
                Button("立即获取二维码") { Task { _ = await update() } }.buttonStyle(.borderedProminent).disabled(loading)
            }
            Text("取码仅在当前页面可见且处于签到窗口时自动进行。").font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Spacer()
        }.padding(24).navigationTitle("签到二维码").navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("完成") { dismiss() } }
        .task(id: phase) {
            image = nil
            guard phase == .active else { return }
            while !Task.isCancelled {
                guard course.isInWindow(at: Date()) else { image = nil; message = "未到签到时间或课程已结束"; return }
                let interval = await update()
                do { try await Task.sleep(for: .seconds(interval ?? 5)) } catch { break }
            }
        }
    }
    @MainActor private func update() async -> TimeInterval? {
        guard phase == .active, !state.demo, state.session != nil, let code = course.signCode else { return nil }
        loading = true; defer { loading = false }
        do {
            let (url, duration) = try await state.qr(code: code)
            try Task.checkCancellation()
            let filter = CIFilter.qrCodeGenerator(); filter.message = Data(url.absoluteString.utf8); filter.correctionLevel = "M"
            guard let output = filter.outputImage, let cg = CIContext().createCGImage(output.transformed(by: CGAffineTransform(scaleX: 10, y: 10)), from: output.extent.applying(CGAffineTransform(scaleX: 10, y: 10))) else {
                throw QingxinError.invalidResponse("二维码生成失败")
            }
            image = UIImage(cgImage: cg); expires = Date().addingTimeInterval(duration); message = "二维码已过期，正在刷新"
            return duration
        } catch { image = nil; message = error is CancellationError ? "已暂停取码" : error.localizedDescription; return nil }
    }
}
