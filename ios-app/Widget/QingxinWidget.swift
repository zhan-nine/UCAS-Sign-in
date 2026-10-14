// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import WidgetKit
import QingxinCore

struct CourseEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?
}

struct CourseProvider: TimelineProvider {
    func placeholder(in context: Context) -> CourseEntry { CourseEntry(date: Date(), snapshot: nil) }
    func getSnapshot(in context: Context, completion: @escaping (CourseEntry) -> Void) {
        completion(CourseEntry(date: Date(), snapshot: read()))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<CourseEntry>) -> Void) {
        let snapshot = read(), now = Date()
        let midnight = SchoolDate.calendar.startOfDay(for: now).addingTimeInterval(24 * 60 * 60)
        var dates = [now, midnight]
        for course in snapshot?.courses(at: now) ?? [] {
            if let begin = course.begin { dates += [begin.addingTimeInterval(-25 * 60), begin] }
            if let end = course.end { dates.append(end) }
        }
        let entries = Set(dates.filter { $0 >= now && $0 <= midnight }).sorted().map { CourseEntry(date: $0, snapshot: snapshot) }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(60 * 60))))
    }
    private func read() -> WidgetSnapshot? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "QingxinAppGroup") as? String,
              let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?.appendingPathComponent("today.json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }
}

struct CourseWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: CourseEntry
    private var rows: [Course] {
        let all = entry.snapshot?.courses(at: entry.date) ?? []
        let upcoming = all.filter { ($0.end ?? .distantPast) > entry.date }
        let sorted = upcoming.isEmpty ? all : upcoming
        return Array(sorted.prefix(family == .systemLarge ? 5 : (family == .systemMedium ? 2 : 1)))
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("清新签到", systemImage: "leaf.fill").font(.caption.bold()).foregroundStyle(Color(red: 0.09, green: 0.49, blue: 0.39))
                Spacer()
                if family != .systemSmall { Text(entry.date.formatted(.dateTime.month().day())).font(.caption).foregroundStyle(.secondary) }
            }
            if rows.isEmpty {
                Spacer()
                Text(isFresh ? "今日暂无课程" : "打开 App 同步课表").font(.headline)
                Spacer()
            } else {
                ForEach(rows) { course in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(course.name).font(.system(family == .systemSmall ? .headline : .subheadline, weight: .semibold)).lineLimit(2)
                        HStack {
                            Text("\(SchoolDate.time(course.begin))—\(SchoolDate.time(course.end))").monospacedDigit()
                            if family != .systemSmall { Spacer(); Text(course.status(at: entry.date)) }
                        }.font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
            if let snapshot = entry.snapshot, isFresh {
                Text("缓存 · \(snapshot.updatedAt.formatted(date: .omitted, time: .shortened)) 更新").font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .containerBackground(Color(red: 0.94, green: 0.98, blue: 0.96), for: .widget)
        .widgetURL(URL(string: "qingxin://today"))
    }
    private var isFresh: Bool { entry.snapshot?.day == SchoolDate.key(entry.date) }
}

@main struct QingxinWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "QingxinCourses", provider: CourseProvider()) { CourseWidgetView(entry: $0) }
            .configurationDisplayName("今日课程")
            .description("查看当前和下一节课，点击打开清新签到。")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
