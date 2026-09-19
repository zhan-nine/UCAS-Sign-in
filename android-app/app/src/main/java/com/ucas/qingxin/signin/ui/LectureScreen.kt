package com.ucas.qingxin.signin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ucas.qingxin.signin.lecture.LectureEntry
import com.ucas.qingxin.signin.lecture.LectureNoticeService
import com.ucas.qingxin.signin.lecture.LectureOrigin
import com.ucas.qingxin.signin.lecture.LectureType

/** 与既有页面统一的主色 / 次要文字色（见 MainActivity）。 */
private val ACCENT = Color(0xFF0F6B4C)
private val TEXT_MAIN = Color(0xFF16362B)
private val TEXT_BODY = Color(0xFF3E5249)
private val TEXT_SUB = Color(0xFF5B6B63)
private val UPCOMING_BG = Color(0xFFE7F4EE)
private val MUTED = Color(0xFF8FA89C)

/** 「已结束」徽标的底色：中性灰，避免被误读成「需要注意」。 */
private val MUTED_BG = Color(0xFFEDF1EF)

/**
 * 「讲座」页面。
 *
 * ## 两个来源，两段列表
 * 页面上的每一条讲座来自两个来源之一，且**来源决定了它有多少信息**：
 * - **预约系统**（需登录，见 [LectureScheduleScreen]）：有讲座日期、起止时间与场地；
 * - **学院网站**（免登录）：只有标题与期次号，实测预告正文多为海报图片，
 *   日期印在图上，抓不出来。
 *
 * 同一场讲座会同时出现在两侧，因此由 `LectureBoard` 按**期次号**把它们并成一条：
 * 时间场地取自预约系统、详情链接取自学院网站。用户看到的就是一条完整的信息。
 *
 * ## 为什么分成「即将开始 / 暂无日期 / 往期」
 * 用户打开这一页的动机几乎总是「接下来有什么讲座要去听」。而往期会随时间无限增长，
 * 混在一起会把真正要看的内容越推越远。因此往期默认**折叠**。
 *
 * 「即将开始」**只收有确切讲座日期的条目**（预约系统）。学院网站只有标题的通知
 * 单独放在「暂无举办日期」—— 发布日 ≠ 讲座日，把它们标成即将开始等于谎称知道日期。
 * 判据见 `LectureBoard`。
 *
 * ## 列表条目一律不可签到
 * 两个来源都给不出 iClass 的 7 位节次编号（预约系统的讲座编号与它不是同一套，
 * 见 `docs/lecture-signin-research.md` §4）。需要签到时走「手动打卡」
 * （[ManualSignScreen]），那里直接吃现场读到的编号。
 */
@Composable
fun LectureScreen(
    state: LectureUiState,
    vm: LectureViewModel,
    onBack: () -> Unit,
    onOpenManualSign: () -> Unit,
    onOpenSchedule: () -> Unit,
) {
    // ViewModel 是 Activity 级的（跨页面存活），进入本页时主动拉一次，保证数据新鲜。
    // 从「讲座预告」页返回时本组合会重新进入，因此这里也会重跑一次 ——
    // 存档里的讲座时间表就是靠这一下立刻显示出来的（见 LectureViewModel.rebuild）。
    LaunchedEffect(Unit) { vm.refresh() }

    val context = LocalContext.current
    // 详情页地址只在「本地配置里填了站点」时才拼得出来；拼不出就不显示跳转入口。
    val openDetail: (LectureEntry) -> Unit = { entry ->
        val url = LectureNoticeService.detailPageUrl(entry.detailUrl)
        if (url.isNotBlank()) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "讲座",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    TestBadge()
                }
                Button(
                    onClick = vm::refresh,
                    enabled = !state.loading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ACCENT,
                        disabledContainerColor = Color(0xFF9BB5AA),
                    ),
                ) { Text(if (state.loading) "刷新中…" else "刷新") }
            }
        }
        item { SourceNote(state) }
        item { ScheduleEntry(state, onOpenSchedule) }
        item { TypeFilterRow(state, vm) }
        item { ManualSignEntry(onOpenManualSign) }
        item { SentinelCard(state, vm) }
        item { NotifyCard(state, vm) }
        if (state.message.isNotBlank()) {
            item { Text(state.message, fontSize = 12.sp, color = TEXT_SUB) }
        }
        if (state.error.isNotBlank()) {
            item { Text(state.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
        }
        if (state.statusMessage.isNotBlank()) {
            item { Text(state.statusMessage, fontSize = 13.sp, color = ACCENT, fontWeight = FontWeight.Medium) }
        }
        if (state.loading) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
        if (state.total == 0 && !state.loading) {
            item { EmptyHint() }
        } else {
            if (state.upcoming.isNotEmpty()) {
                item { SectionHeader("即将开始", "${state.upcoming.size} 场") }
                items(state.upcoming, key = { it.key }) { entry ->
                    EntryRow(entry, onOpen = { openDetail(entry) })
                }
            } else if (!state.loading) {
                item { SectionHeader("即将开始", "暂无（需先登录预约系统读取时间表）") }
            }
            if (state.undated.isNotEmpty()) {
                item { SectionHeader("暂无举办日期", "${state.undated.size} 条学院网站通知") }
                items(state.undated, key = { it.key }) { entry ->
                    EntryRow(entry, onOpen = { openDetail(entry) })
                }
            }
            if (state.past.isNotEmpty()) {
                item { PastHeader(state, vm) }
                if (state.pastExpanded) {
                    items(state.past, key = { it.key }) { entry ->
                        EntryRow(entry, onOpen = { openDetail(entry) })
                    }
                }
            }
        }
    }
}

/** 段落标题（左标题 + 右计数）。 */
@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(trailing, fontSize = 12.sp, color = TEXT_SUB)
    }
}

/**
 * 「往期」段的标题行，同时是折叠开关。
 *
 * 用整行可点而不是一个小箭头：这一行是页面上最容易点错的地方
 * （用户想滚动时误触），把热区做成整行反而更好按，而误触的代价只是展开 —— 无损。
 */
@Composable
private fun PastHeader(state: LectureUiState, vm: LectureViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = vm::togglePast)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("往期", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "${state.past.size} 场 · ${if (state.pastExpanded) "收起" else "展开"}",
            fontSize = 12.sp,
            color = ACCENT,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 数据来源说明 + 「讲座预告（预约系统）」入口。
 *
 * 两句话必须写清楚，否则用户无法解释自己看到的东西：
 * 1. **为什么有些条目有时间、有些没有** —— 有时间的是从预约系统读来的，
 *    没有的是学院网站的通知（那里只有发布日）；
 * 2. **那份有时间的数据有多新** —— 它由用户自己登录后读取，因此必须显示读取时间。
 */
@Composable
private fun SourceNote(state: LectureUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "讲座相关功能仍在测试中：数据来源、分类判据与签到链路都可能随版本调整，" +
                "请以预约系统与学院网站的原始页面为准。",
            fontSize = 11.sp,
            color = Color(0xFF8A5300),
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "有时间的条目来自讲座预约系统（需在应用内登录后读取）；" +
                "只有标题的条目来自人文学院网站发布的公开通知 —— 那里只有发布日，" +
                "没有讲座时间与场地，具体时间以预约系统为准。",
            fontSize = 11.sp,
            color = TEXT_SUB,
            lineHeight = 16.sp,
        )
        Text(
            if (state.scheduleReadAt.isBlank()) {
                "尚未从预约系统读过讲座时间表，因此下面不会有带时间的条目。"
            } else {
                "预约系统数据读取于 ${state.scheduleReadAt}，共 ${state.scheduleCount} 场。"
            },
            fontSize = 11.sp,
            color = if (state.scheduleReadAt.isBlank()) Color(0xFFB26A00) else TEXT_SUB,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 「讲座预告（预约系统）」入口卡片。
 *
 * 与「手动打卡」入口同理：这一页看完之后想去读最新的时间表是自然的连续动作，
 * 因此把它放在筛选栏之前（数据新鲜度直接决定下面内容的可信度，位置靠前是对的）。
 */
@Composable
private fun ScheduleEntry(state: LectureUiState, onOpen: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("讲座预告（预约系统）", fontWeight = FontWeight.SemiBold)
                    TestBadge()
                }
                Text(
                    if (state.scheduleReadAt.isBlank()) {
                        "登录后读取讲座时间表，拿到日期与场地"
                    } else {
                        "已读取 ${state.scheduleCount} 场 · ${state.scheduleReadAt}"
                    },
                    fontSize = 12.sp,
                    color = TEXT_SUB,
                )
            }
            Text("进入 ›", fontSize = 13.sp, color = ACCENT, fontWeight = FontWeight.Medium)
        }
    }
}

/** 栏目筛选栏：横滑 chips，三个按钮**互斥**（「全部」在最左）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeFilterRow(state: LectureUiState, vm: LectureViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.selectedType == null,
            onClick = { vm.selectType(null) },
            label = { Text("全部") },
            colors = lectureChipColors(),
        )
        LectureType.entries.forEach { type ->
            FilterChip(
                selected = state.selectedType == type,
                onClick = { vm.selectType(type) },
                label = { Text(type.label) },
                colors = lectureChipColors(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun lectureChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.White,
    labelColor = TEXT_BODY,
    selectedContainerColor = ACCENT,
    selectedLabelColor = Color.White,
)

/**
 * 「手动打卡」入口卡片。
 *
 * 这里**不再内嵌签到表单**：手动打卡与讲座没有耦合（签到通道就是普通课程那条），
 * 而它的使用场景是「站在班牌前立刻要打卡」—— 那条路径上不该要求用户先进讲座页、
 * 再展开一个折叠卡片、还得等讲座通知抓完。现在它有了独立页面
 * （见 [ManualSignScreen]），本卡片只负责把用户送过去。
 *
 * 之所以仍然在讲座页留一个入口：用户在这个页面看完讲座预告后，
 * 接着想去打卡是自然的连续动作，砍掉入口会让人以为功能被删了。
 */
@Composable
private fun ManualSignEntry(onOpen: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("手动打卡", fontWeight = FontWeight.SemiBold)
                Text(
                    "录入现场读到的 7 位编号 / 32 位标识",
                    fontSize = 12.sp,
                    color = TEXT_SUB,
                )
            }
            Text("进入 ›", fontSize = 13.sp, color = ACCENT, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 「找下一场讲座」哨兵卡片。
 *
 * 与下面的「新预告通知」是**两种不同的能力**，因此文案必须把差别说清楚：
 * - 新预告通知：学院网站发了新通知（内容权威，但要等老师发）；
 * - 哨兵：课程注册表里开出了新场次（**更早**，实测可领先公开源数月，
 *   但**只有名称**，没有时间与场地）。
 *
 * 默认关闭，且**单独一个开关**：它会周期性访问校内后台，
 * 虽然只是几十次只读请求，也应当由用户明确同意。
 */
@Composable
private fun SentinelCard(state: LectureUiState, vm: LectureViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("提前发现下一场讲座", fontWeight = FontWeight.SemiBold)
                        TestBadge()
                    }
                    Text(
                        "从课程注册表读取新开出的场次",
                        fontSize = 12.sp,
                        color = TEXT_SUB,
                    )
                }
                Switch(
                    checked = state.sentinelEnabled,
                    onCheckedChange = vm::setSentinelEnabled,
                    // 未配置端点或基点编号时不给可点态：开了也只会立刻失败，
                    // 不如把开关禁用并说明原因。
                    enabled = state.sentinelAvailable,
                )
            }
            if (!state.sentinelAvailable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前安装包未配置注册表端点或基点编号，此功能不可用。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            if (state.sentinelLastRunAt.isNotBlank() || state.sentinelSummary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        if (state.sentinelLastRunAt.isNotBlank()) append("上次扫描 ${state.sentinelLastRunAt}")
                        if (state.sentinelSummary.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(state.sentinelSummary)
                        }
                    },
                    fontSize = 12.sp,
                    color = TEXT_BODY,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (state.sentinelFound.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.sentinelFound.joinToString("\n") { "· $it" },
                    fontSize = 12.sp,
                    color = ACCENT,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::scanSentinelNow,
                enabled = state.sentinelEnabled && !state.sentinelScanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ACCENT,
                    disabledContainerColor = Color(0xFF9BB5AA),
                ),
            ) {
                Text(if (state.sentinelScanning) "扫描中…" else "立即扫描", fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "只读课程名，不发任何写操作；每轮只扫「上次讲座」之后的一小段编号。" +
                    "注册表里能识别的场次是**带期次号**的那些（如明德讲堂 M1167）——" +
                    "它不含时间与场地，所以这里只能告诉你「有新讲座、叫什么」，" +
                    "具体时间仍以预约系统与学院网站通知为准。",
                fontSize = 11.sp,
                color = TEXT_SUB,
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * 「新预告通知」开关。
 *
 * 文案必须说明它**不是什么**：它只在应用拉到新通知之后推送，
 * 不是定时轮询、也不能预测讲座何时开始。
 */
@Composable
private fun NotifyCard(state: LectureUiState, vm: LectureViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("新讲座通知", fontWeight = FontWeight.SemiBold)
                    TestBadge()
                }
                Text(
                    "学院网站发布新的讲座通知时提醒一次",
                    fontSize = 12.sp,
                    color = TEXT_SUB,
                )
            }
            Switch(
                checked = state.notifyEnabled,
                onCheckedChange = vm::setNotifyEnabled,
            )
        }
    }
}

/** 讲座条目行：时间/场地 → 期次号 + 徽标 → 标题 → 来源标签 + 详情入口。 */
@Composable
private fun EntryRow(entry: LectureEntry, onOpen: () -> Unit) {
    val accent = when {
        entry.ended -> MUTED
        entry.timed -> ACCENT
        else -> MUTED
    }
    val hasDetail = LectureNoticeService.detailPageUrl(entry.detailUrl).isNotBlank()
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent, RoundedCornerShape(999.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .then(if (hasDetail) Modifier.clickable(onClick = onOpen) else Modifier),
            ) {
                // 有确切时间的条目把时间放在**第一行**：它是判断「能不能去」的第一依据，
                // 标题再长也不该把它挤到下面去。
                if (entry.scheduleLabel.isNotBlank()) {
                    Text(
                        entry.scheduleLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (entry.ended) MUTED else ACCENT,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (entry.sessionNo.isNotBlank()) SessionTag(entry.sessionNo)
                    // 没有时间的条目退而显示发布日期：它至少能说明这条消息有多新。
                    if (!entry.timed && entry.publishedDate.isNotBlank()) {
                        Text(
                            formatNoticeDate(entry.publishedDate),
                            fontSize = 12.sp,
                            color = TEXT_BODY,
                        )
                    }
                    if (entry.ended) EndedTag()
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = TEXT_MAIN,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OriginTag(entry.origin)
                    Spacer(Modifier.width(8.dp))
                    // 详情页在校内站点上；未配置站点地址时不给可点击态，避免点出一次必然失败。
                    if (hasDetail) {
                        Text("查看详情 ›", fontSize = 11.sp, color = ACCENT, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** 期次号徽标（如 `M1167` / `213`）。 */
@Composable
private fun SessionTag(sessionNo: String) {
    Box(
        Modifier
            .background(ACCENT, RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(sessionNo, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 「已结束」徽标。
 *
 * 两个来源的判据不同，但都是**站内证据**，因此可以共用一个标注：
 * - 有确切时间的（预约系统）⇒ 讲座结束时刻已过；
 * - 没有时间的（学院网站）⇒ 同一期次号下已经出现报道。
 *
 * 没这个标记**不代表讲座还没开始**：只有通知、且报道还没发出来的那段时间里，
 * 一场已经讲完的讲座仍会留在「即将开始」段。这一点在 [SourceNote] 里已如实说明。
 */
@Composable
private fun EndedTag() {
    Box(
        Modifier
            .background(MUTED_BG, RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text("已结束", fontSize = 10.sp, color = MUTED, fontWeight = FontWeight.Medium)
    }
}

/**
 * 来源标签。
 *
 * 必须标出来：它直接决定这一条有多可信、以及为什么有的条目没有时间。
 * 用一个显式的枚举（[LectureOrigin]）而不是 `timed` 布尔值，
 * 是为了让文案与取值一一对应，不必在界面上再推断一次。
 */
@Composable
private fun OriginTag(origin: LectureOrigin) {
    val fg = when (origin) {
        LectureOrigin.SCHEDULE -> ACCENT
        LectureOrigin.NOTICE -> TEXT_SUB
    }
    Box(
        Modifier
            .background(UPCOMING_BG, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(origin.label, fontSize = 11.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

/**
 * 空态引导文案。
 *
 * 措辞必须诚实：这里展示的是「预约系统读到的 + 学院网站已发布的」，
 * 而不是「校内全部讲座」。所以「这里没有」不等于「没有讲座」。
 */
@Composable
private fun EmptyHint() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("当前筛选下暂无讲座", fontWeight = FontWeight.Bold)
            Text(
                "本页只收录预约系统的讲座时间表与人文学院网站发布的通知，不覆盖全部讲座。" +
                    "若还没有读过预约系统的讲座时间表，请点上方「讲座预告（预约系统）」登录后读取；" +
                    "也可以选「全部」看看其他栏目，或点右上角「刷新」重试。",
                fontSize = 12.sp,
                color = TEXT_SUB,
                lineHeight = 17.sp,
            )
        }
    }
}

/** `yyyy-MM-dd` → `M月d日`；无法识别时原样展示，不做静默丢弃。 */
private fun formatNoticeDate(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    if (digits.length != 8) return raw.trim().ifBlank { "—" }
    val month = digits.substring(4, 6).toIntOrNull() ?: return raw.trim()
    val day = digits.substring(6, 8).toIntOrNull() ?: return raw.trim()
    return "${month}月${day}日"
}
