package com.ucas.qingxin.signin.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ucas.qingxin.signin.R
import com.ucas.qingxin.signin.attendance.AutoSignExclusions
import com.ucas.qingxin.signin.attendance.KeepAliveHelper
import com.ucas.qingxin.signin.data.AttendanceUiStatus
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.update.UpdateRelease
import com.ucas.qingxin.signin.update.UpdateReleases
import com.ucas.qingxin.signin.util.CourseTimeDisplay
import com.ucas.qingxin.signin.util.DateInput
import com.ucas.qingxin.signin.widget.TodayCourseWidgetReceiver
import com.ucas.qingxin.signin.widget.WidgetPinHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private enum class AppScreen { Home, Schedule, Lecture, LectureSchedule, Manual, Settings }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val lectureVm: LectureViewModel by viewModels()

    /**
     * 课表浏览页的 ViewModel。
     *
     * 与 [lectureVm] 一样是**独立**的：它只读课表、不参与签到，
     * 因此不需要（也不应该）与 [MainViewModel] 共享任何状态。
     */
    private val scheduleVm: ScheduleViewModel by viewModels()

    /**
     * 手动打卡页的 ViewModel。
     *
     * 同样是**独立**的：手动打卡只认「现场读到的编号」，不需要讲座通知、
     * 也不需要当前课表，因此与 [lectureVm] 共享状态只会带来无谓的耦合。
     */
    private val manualVm: ManualSignViewModel by viewModels()

    /**
     * 「讲座预告（预约系统）」页的 ViewModel。
     *
     * **独立**是有意的：那一页的职责是「带读取功能的浏览器」（见 [LectureScheduleScreen]），
     * 它不需要讲座通知、也不需要课表；而它读到的讲座时间表通过本机存档
     * 与 [LectureViewModel] 交汇 —— 两个 ViewModel 之间不直接引用，
     * 这样「谁先创建、谁先写入」都不会出错。
     */
    private val lectureScheduleVm: LectureScheduleViewModel by viewModels()
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        setContent {
            val state by vm.ui.collectAsStateWithLifecycle()
            val lectureState by lectureVm.ui.collectAsStateWithLifecycle()
            val lectureScheduleState by lectureScheduleVm.ui.collectAsStateWithLifecycle()
            val scheduleState by scheduleVm.ui.collectAsStateWithLifecycle()
            val manualState by manualVm.ui.collectAsStateWithLifecycle()
            // 用 rememberSaveable：旋屏 / 进程重建后仍停留在同一页，而不是突然跳回首页。
            var screen by rememberSaveable { mutableStateOf(AppScreen.Home) }
            // 除首页外的子页面（设置 / 讲座）都支持返回键回首页。
            val subScreen = state.authenticated && screen != AppScreen.Home
            // 系统返回键 / 手势返回：在子页面应回到首页，而不是直接退出 App。
            // 弹窗（AlertDialog）自带更高的返回优先级，会先关掉弹窗，不受此处影响。
            BackHandler(enabled = subScreen) { screen = AppScreen.Home }
            QingxinTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        when {
                            state.restoring -> LoadingPane("恢复登录状态…")
                            !state.authenticated -> LoginScreen(state, vm)
                            screen == AppScreen.Settings -> SettingsScreen(
                                state = state,
                                vm = vm,
                                onBack = { screen = AppScreen.Home },
                            )
                            screen == AppScreen.Lecture -> LectureScreen(
                                state = lectureState,
                                vm = lectureVm,
                                onBack = { screen = AppScreen.Home },
                                onOpenManualSign = { screen = AppScreen.Manual },
                                onOpenSchedule = { screen = AppScreen.LectureSchedule },
                            )
                            screen == AppScreen.LectureSchedule -> LectureScheduleScreen(
                                state = lectureScheduleState,
                                vm = lectureScheduleVm,
                                onBack = { screen = AppScreen.Lecture },
                            )
                            screen == AppScreen.Manual -> ManualSignScreen(
                                state = manualState,
                                vm = manualVm,
                                onBack = { screen = AppScreen.Home },
                            )
                            screen == AppScreen.Schedule -> ScheduleScreen(
                                state = scheduleState,
                                vm = scheduleVm,
                                onBack = { screen = AppScreen.Home },
                            )
                            else -> HomeScreen(
                                state = state,
                                vm = vm,
                                onOpenLecture = { screen = AppScreen.Lecture },
                                onOpenSchedule = { screen = AppScreen.Schedule },
                                onOpenManualSign = { screen = AppScreen.Manual },
                                onOpenSettings = { screen = AppScreen.Settings },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun QingxinTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF0F6B4C),
        onPrimary = Color.White,
        secondary = Color(0xFF2F6F5E),
        background = Color(0xFFF3F7F4),
        surface = Color(0xFFFFFFFF),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun LoadingPane(text: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text)
    }
}

@Composable
private fun LoginScreen(state: AppUiState, vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFE8F3EE), Color(0xFFF7F4EC)),
                ),
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "轻新签到",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text("连接国科大 iClass / 轻新课堂", color = Color(0xFF4A5C55))
        Spacer(Modifier.height(16.dp))
        Text(
            "可使用两套账号（共用下方同一组输入框）：",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF16362B),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "1. SEP 邮箱 + SEP 密码\n" +
                "2. 轻新课堂学号 + 轻新课堂密码（默认密码多为 Ucas@2025）",
            fontSize = 12.sp,
            color = Color(0xFF4A5C55),
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = state.studentNoInput,
            onValueChange = vm::onStudentNoChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("学号 / SEP 邮箱") },
            placeholder = { Text("学号或邮箱") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.passwordInput,
            onValueChange = vm::onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            placeholder = { Text("SEP 密码或轻新课堂密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = vm::login,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "登录中…" else "登录") }
        if (state.error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(state.error, color = MaterialTheme.colorScheme.error)
        }
        if (state.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(state.message)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "HTTPS + 系统证书校验 · 凭据加密存储",
            fontSize = 12.sp,
            color = Color(0xFF6B7C74),
        )
    }
}

@Composable
private fun HomeScreen(
    state: AppUiState,
    vm: MainViewModel,
    onOpenLecture: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenManualSign: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 进入主页时按需自动检查更新（有 12 小时节流，见 MainViewModel.onEnterHome）。
    LaunchedEffect(Unit) { vm.onEnterHome() }
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "轻新签到",
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
                    )
                    Text(
                        "学号 ${state.studentNo}",
                        color = Color(0xFF4A5C55),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 三个入口按钮 + 标题挤在一行：把按钮的横向内边距收窄，
                // 否则在 360dp 宽度的机型上学号会被压成两行。
                TextButton(
                    onClick = onOpenSchedule,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("课表") }
                TextButton(
                    onClick = onOpenLecture,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("讲座") }
                TextButton(
                    onClick = onOpenSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("设置") }
            }
        }
        // 更新提示横幅。
        //
        // 放在标题行正下方、手动打卡之前：它是一条**一次性**的信息（用户处理完就消失），
        // 而手动打卡是常驻入口；一次性信息压在日常入口之上会让页面长期变重，
        // 但它又必须足够靠前 —— 放在页面底部等于没有提示。
        //
        // 三种「不再打扰」的语义刻意分开（见 UpdateUiState）：
        // 「稍后」= 本次会话不再显示，「不再提示」= 忽略这一个版本，
        // 彻底关掉自动检查在设置页。
        item { UpdateBanner(state, vm) }
        // 手动打卡的首页入口。
        //
        // 放在标题行**下方**而不是挤进右上角那排按钮：那一行已有三个入口，
        // 再塞一个会把学号压成两行（360dp 机型上实测如此）。
        // 位置选在「今日课程」之前，是因为它的使用场景是「站在班牌前，
        // 一刻也不想等」—— 恰好也是「自动打卡没能覆盖」时的兜底手段，
        // 因此不该被压到页面底部去翻。
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenManualSign)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("手动打卡", fontWeight = FontWeight.Bold)
                        Text(
                            "录入现场读到的 7 位编号 / 32 位标识",
                            fontSize = 12.sp,
                            color = Color(0xFF5B6B63),
                        )
                    }
                    Text("进入 ›", fontSize = 13.sp, color = Color(0xFF0F6B4C), fontWeight = FontWeight.Medium)
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("今日课程", fontWeight = FontWeight.Bold)
                    if (state.fromCache) {
                        Text("（缓存）", color = Color(0xFFB26A00), fontSize = 12.sp)
                    }
                    Text(state.message, fontSize = 12.sp, color = Color(0xFF5B6B63))
                    Spacer(Modifier.height(8.dp))
                    Text("当前：${state.current?.name ?: "无"}")
                    Text("下一节：${state.next?.name ?: "无"}")
                    Text(
                        "二维码：${state.selected?.let { "${CourseTimeDisplay.hm(it.beginTime)} ${it.name}" } ?: "无"}",
                        color = Color(0xFF0F6B4C),
                        fontWeight = FontWeight.Medium,
                    )
                    Text("状态：${statusLabel(state.status)}")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::refreshCourses, enabled = !state.busy) { Text("刷新课程") }
                        Button(
                            onClick = vm::signSelected,
                            enabled = vm.canSignNow(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0F6B4C),
                                disabledContainerColor = Color(0xFF9BB5AA),
                            ),
                        ) { Text(if (state.signing) "签到中…" else "一键签到") }
                    }
                    if (state.usingManualQr) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = vm::useDefaultQrCourse) {
                            Text("使用当前课二维码")
                        }
                    }
                    if (state.error.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            QrCard(state)
        }
        item {
            Text("全部课程", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "共 ${state.courses.size} 节 · 点选可切换二维码",
                fontSize = 12.sp,
                color = Color(0xFF5B6B63),
            )
        }
        items(state.courses, key = { it.id + it.beginTime }) { course ->
            CourseRow(
                course = course,
                selected = state.selected?.id == course.id,
                isCurrent = state.current?.id == course.id ||
                    (state.current != null &&
                        course.name.trim() == state.current.name.trim() &&
                        course.beginTime == state.current.beginTime),
                onClick = { vm.selectCourse(course) },
            )
        }
    }
}

/**
 * 「课程表」页面：输入 / 选择任意日期，查看那一天的课表。
 *
 * ## 为什么与签到彻底分离
 * 首页那套能力（二维码、一键签到）隐含一个前提——「看的课就是今天的课」。
 * 把别的日期接进同一条链路会让「现在到底能不能签」变得难以解释，而签到窗口
 * 只有一节多课的时间，容不下这种歧义。因此这一页**只读**：
 * 没有二维码、没有签到按钮，课程行也不可点选。
 *
 * 对应的数据侧约束见 [ScheduleViewModel] 与 `CourseRepository.browseDay`：
 * 浏览不会写 `CourseRepository.courses`，因此也不会影响小部件与自动签到。
 */
@Composable
private fun ScheduleScreen(
    state: ScheduleUiState,
    vm: ScheduleViewModel,
    onBack: () -> Unit,
) {
    // 每次进入本页都重取一次：缓存优先渲染，网络结果随后覆盖。
    // 这样退出登录换账号后不需要任何额外的失效逻辑。
    LaunchedEffect(Unit) { vm.onEnter() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(
                    "课程表",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        item { ScheduleDateCard(state, vm) }
        item { ScheduleNoticeCard(state) }
        item { ScheduleSummary(state, vm) }
        if (state.courses.isEmpty()) {
            item { ScheduleEmptyOrLoading(state) }
        } else {
            // key 里带上 day：同一天内 id 若重复（实测服务端偶有重复条目）会直接抛异常闪退，
            // 而列表顺序还可能因「周课表回退」而跨天。
            items(state.courses, key = { it.id + it.day + it.beginTime }) { course ->
                ScheduleCourseRow(course)
            }
        }
    }
}

/** 日期输入 + 前后一天 + 今天 + 系统日期选择器。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDateCard(state: ScheduleUiState, vm: ScheduleViewModel) {
    var showPicker by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("查看日期", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = state.dateInput,
                onValueChange = vm::onDateInputChange,
                singleLine = true,
                isError = state.inputError,
                label = { Text("日期") },
                placeholder = { Text("2026-09-20") },
                supportingText = {
                    Text(
                        if (state.inputError) {
                            "看不懂这个日期，试试 2026-09-20 / 20260920 / 9月20日"
                        } else {
                            "可输入 2026-09-20、20260920、2026年9月20日、9-20"
                        },
                        fontSize = 11.sp,
                        color = if (state.inputError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color(0xFF5B6B63)
                        },
                    )
                },
                // 用文本键盘而不是数字键盘：日期里允许出现 `-` `/` `月` `日`，
                // 数字键盘会把这些字符全部挡住，反而没法输入。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submitDateInput() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.stepDay(-1) },
                    modifier = Modifier.weight(1f),
                ) { Text("前一天") }
                OutlinedButton(
                    onClick = vm::goToday,
                    modifier = Modifier.weight(1f),
                ) { Text("今天") }
                OutlinedButton(
                    onClick = { vm.stepDay(1) },
                    modifier = Modifier.weight(1f),
                ) { Text("后一天") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::submitDateInput,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F6B4C)),
                ) { Text("查询") }
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.weight(1f),
                ) { Text("选择日期") }
            }
        }
    }
    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.selectedDate
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            // 选择器返回的是 **UTC 当日零点**，必须按 UTC 解读：
                            // 用本地时区换算会让东八区整体差一天（拿到前一天）。
                            vm.selectDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        showPicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** 说清楚这一页不签到 —— 避免用户在这里找二维码，也避免误以为能看到别的日期的签到状态。 */
@Composable
private fun ScheduleNoticeCard(state: ScheduleUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F7F4)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (state.isToday) "这就是今天" else "本页仅供查看，不会签到",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Color(0xFF0F6B4C),
            )
            Text(
                if (state.isToday) {
                    "回到首页即可刷新课程、查看二维码并签到。"
                } else {
                    "签到只对今天的课有效，因此这里不提供二维码。要看二维码请回首页。"
                },
                fontSize = 11.sp,
                color = Color(0xFF5B6B63),
            )
        }
    }
}

/** 标题：日期 + 星期 + 节数 + 刷新。 */
@Composable
private fun ScheduleSummary(state: ScheduleUiState, vm: ScheduleViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(DateInput.prettyLabel(state.selectedDate), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (state.isToday) {
                Spacer(Modifier.width(6.dp))
                Text("今天", fontSize = 11.sp, color = Color(0xFF0F6B4C), fontWeight = FontWeight.SemiBold)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // 还没有任何内容可显示时别报「共 0 节」——那读起来像「这天没课」，
                // 而实际上只是还没同步回来。
                if (state.loading && state.total == 0) {
                    "正在同步…"
                } else {
                    buildString {
                        append("共 ${state.total} 节")
                        if (state.fromCache) append(" · 本地缓存")
                        // 缓存已铺在屏幕上、网络结果还在路上时给一个暗示，
                        // 否则用户会以为看到的就是最新数据。
                        if (state.loading) append(" · 同步中…")
                    }
                },
                fontSize = 12.sp,
                color = Color(0xFF5B6B63),
            )
            TextButton(onClick = vm::refresh, enabled = !state.loading) { Text("刷新") }
        }
        if (state.message.isNotBlank()) {
            Text(state.message, fontSize = 11.sp, color = Color(0xFF6B7C74))
        }
    }
}

/** 无课 / 加载中 / 出错，三种状态共用一块占位卡片。 */
@Composable
private fun ScheduleEmptyOrLoading(state: ScheduleUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在同步 ${DateInput.display(state.selectedDate)} 的课表…", fontSize = 13.sp)
                }
                state.error.isNotBlank() -> Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
                else -> {
                    Text(
                        "${DateInput.prettyLabel(state.selectedDate)} 没有课程",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    Text(
                        "可以换一天看看，或点上方「刷新」重新同步。",
                        fontSize = 12.sp,
                        color = Color(0xFF5B6B63),
                    )
                }
            }
        }
    }
}

/** 只读的课程行（对比首页 [CourseRow]：没有选中态、不可点选）。 */
@Composable
private fun ScheduleCourseRow(course: Course) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(56.dp)
                    .background(Color(0xFF8FA89C), RoundedCornerShape(999.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    CourseTimeDisplay.range(course.beginTime, course.endTime),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF3E5249),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    course.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color(0xFF16362B),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "教师：${course.teacher.ifBlank { "—" }}",
                    fontSize = 13.sp,
                    color = Color(0xFF5B6B63),
                )
                // 看历史日期时「那天签没签」本身就是有用的信息，因此这里保留签到状态；
                // 它只是服务端返回的一个事实，不附带任何操作。
                if (course.signed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已签到",
                        fontSize = 11.sp,
                        color = Color(0xFF2E7D4F),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * 主页的更新提示横幅。
 *
 * 只在「检测到更高版本、该版本没被忽略、用户也没点稍后」时出现
 * （判据集中在 [UpdateUiState.showBanner]）。
 *
 * **网络失败时这里什么都不显示**：一次断网不该让主页上多出一句用户看不懂的报错，
 * 而且他此刻也做不了什么；失败信息只出现在设置页的「关于 / 更新」卡片里，
 * 那里才是用户主动来问「有没有新版」的地方。
 */
@Composable
private fun UpdateBanner(state: AppUiState, vm: MainViewModel) {
    val update = state.update
    val release = update.available
    if (!update.showBanner || release == null) return
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6E5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "发现新版本 ${release.version}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "当前 ${update.currentVersion}",
                    fontSize = 12.sp,
                    color = Color(0xFF5B6B63),
                )
            }
            val summary = UpdateReleases.summary(release.notes)
            Text(
                summary.ifBlank { release.title },
                fontSize = 12.sp,
                color = Color(0xFF5B6B63),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { openReleasePage(context, release) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F6B4C)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("去 GitHub 更新", fontSize = 14.sp) }
                TextButton(onClick = vm::dismissUpdate) { Text("稍后") }
                TextButton(onClick = vm::ignoreUpdateVersion) { Text("不再提示") }
            }
        }
    }
}

/**
 * 设置页的「关于 / 更新」卡片。
 *
 * 与主页横幅的分工：横幅负责「让用户看到」，这里负责「让用户控制与查询」——
 * 当前版本号、自动检查开关、手动检查、以及已忽略版本的恢复入口都在这里。
 * 已忽略的版本**仍然显示**，而不是假装不存在：用户要能知道自己当初忽略了什么。
 */
@Composable
private fun UpdateCard(state: AppUiState, vm: MainViewModel) {
    val update = state.update
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("关于 / 更新", fontWeight = FontWeight.Bold)
            Text(
                "当前版本 ${update.currentVersion}（${update.currentCode}）",
                fontSize = 13.sp,
                color = Color(0xFF4A5C55),
            )
            SettingSwitch("自动检查更新", update.autoCheck) { vm.setAutoCheckUpdate(it) }
            Text(
                "进入主页时自动检查一次（约 12 小时一次），发现新版本会在主页提示；" +
                    "也可以随时用下面的按钮手动检查。",
                fontSize = 11.sp,
                color = Color(0xFF6B7C74),
            )
            OutlinedButton(
                onClick = { vm.checkForUpdate(auto = false) },
                enabled = !update.checking,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (update.checking) "检查中…" else "检查更新") }
            val release = update.available
            if (release != null) {
                Text(
                    "发现新版本 ${release.version}：" +
                        UpdateReleases.summary(release.notes).ifBlank { release.title },
                    fontSize = 12.sp,
                    color = Color(0xFF0F6B4C),
                    fontWeight = FontWeight.Medium,
                )
                OutlinedButton(
                    onClick = { openReleasePage(context, release) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("去 GitHub 更新") }
            }
            if (update.ignoredTag.isNotBlank()) {
                Text(
                    "已忽略版本 ${update.ignoredTag}，不再在主页提示",
                    fontSize = 12.sp,
                    color = Color(0xFF5B6B63),
                )
                OutlinedButton(
                    onClick = vm::clearIgnoredVersion,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("恢复提示") }
            }
            if (update.status.isNotBlank()) {
                Text(update.status, fontSize = 12.sp, color = Color(0xFF0F6B4C))
            }
            if (update.error.isNotBlank()) {
                Text(
                    update.error,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 用系统浏览器打开 Release 页。
 *
 * 走浏览器而不是在应用内下载：应用不做安装器，也不该替用户决定下载什么文件；
 * Release 页上有说明正文与资产列表，用户能看到自己要装的是什么。
 * 没有可用浏览器（`ActivityNotFoundException`）时静默忽略，不让一次点击崩掉应用。
 */
private fun openReleasePage(context: Context, release: UpdateRelease) {
    if (release.pageUrl.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
    }
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val activity = LocalContext.current as? ComponentActivity
    // 每次回到前台都重新核对保活状态，并借机重试一次守护服务（冷启动时可能被系统拒绝）。
    LifecycleResumeEffect(Unit) {
        vm.refreshKeepAlive()
        onPauseOrDispose { }
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
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("签到偏好", fontWeight = FontWeight.Bold)
                    SettingSwitch("签到通知", state.settings.notifyEnabled) {
                        vm.updateSettings(notify = it)
                    }
                }
            }
        }
        item { AutoSignCard(state, vm) }
        item { AutoSignExcludeCard(state, vm) }
        item { KeepAliveCard(state, vm) }
        item { UpdateCard(state, vm) }
        item {
            val context = LocalContext.current
            var showGuide by remember { mutableStateOf(false) }
            val isColorOs = remember {
                com.ucas.qingxin.signin.widget.VendorRom.isColorOs(context)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("桌面小部件", fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.widget_pin_permission_hint),
                        fontSize = 12.sp,
                        color = Color(0xFFB42318),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(
                            R.string.widget_pin_detected_launcher,
                            WidgetPinHelper.launcherLabel(context),
                        ),
                        fontSize = 12.sp,
                        color = Color(0xFF5B6B63),
                    )
                    Text(
                        WidgetPinHelper.romHint(context),
                        fontSize = 12.sp,
                        color = Color(0xFF5B6B63),
                    )
                    Text(
                        stringResource(R.string.widget_pin_size_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    WidgetPinHelper.Spec.values().forEach { spec ->
                        OutlinedButton(
                            onClick = { activity?.let { WidgetPinHelper.requestPin(it, spec) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("${spec.sizeLabel} · ${stringResource(spec.labelRes)}") }
                    }
                    OutlinedButton(
                        onClick = { showGuide = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.widget_pin_guide)) }
                    OutlinedButton(
                        onClick = {
                            activity?.let { WidgetPinHelper.openAppDetails(it) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.widget_pin_open_permission)) }
                    OutlinedButton(
                        onClick = { activity?.let { WidgetPinHelper.openAutoStart(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.widget_pin_open_autostart)) }
                    if (isColorOs) {
                        OutlinedButton(
                            onClick = { activity?.let { WidgetPinHelper.openColorOsShelf(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.widget_pin_open_shelf)) }
                    }
                }
            }
            if (showGuide) {
                AlertDialog(
                    onDismissRequest = { showGuide = false },
                    confirmButton = {
                        TextButton(onClick = { showGuide = false }) {
                            Text(stringResource(R.string.widget_pin_guide_close))
                        }
                    },
                    title = { Text(stringResource(R.string.widget_pin_guide_title)) },
                    text = { Text(WidgetPinHelper.manualGuide(context)) },
                )
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账号", fontWeight = FontWeight.Bold)
                    Text("学号 ${state.studentNo}", color = Color(0xFF4A5C55))
                    Button(
                        onClick = {
                            vm.logout()
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFB42318),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("退出登录") }
                }
            }
        }
        item {
            var showLicense by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showLicense = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.license_entry)) }
            if (showLicense) {
                AlertDialog(
                    onDismissRequest = { showLicense = false },
                    confirmButton = {
                        TextButton(onClick = { showLicense = false }) {
                            Text(stringResource(R.string.license_close))
                        }
                    },
                    title = { Text(stringResource(R.string.license_entry)) },
                    text = { Text(stringResource(R.string.license_summary), fontSize = 12.sp) },
                )
            }
        }
    }
}

@Composable
private fun AutoSignCard(state: AppUiState, vm: MainViewModel) {
    val ka = state.keepAlive
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.auto_sign_card_title), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.auto_sign_card_desc),
                fontSize = 12.sp,
                color = Color(0xFF5B6B63),
            )
            SettingSwitch(stringResource(R.string.auto_sign_enable), state.settings.autoSignEnabled) {
                vm.updateSettings(autoSign = it)
            }
            SettingSwitch(stringResource(R.string.keepalive_low_power), state.settings.lowPowerMode) {
                vm.updateSettings(lowPower = it)
            }
            Text(
                stringResource(R.string.keepalive_low_power_desc),
                fontSize = 11.sp,
                color = Color(0xFF6B7C74),
            )
            if (state.settings.autoSignEnabled) {
                Text(
                    if (ka.estimatedWakeupsToday > 0) {
                        stringResource(R.string.keepalive_wakeup_budget, ka.estimatedWakeupsToday)
                    } else {
                        stringResource(R.string.keepalive_wakeup_budget_unknown)
                    },
                    fontSize = 11.sp,
                    color = Color(0xFF6B7C74),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 「不自动打卡的课程」设置卡片。
 *
 * ## 为什么有两个开关
 * 两种需求在现实中同时存在，且语义不同：
 * - **今日跳过**：只对今天生效，跨天自动失效（「就今天不想签」）；
 * - **长期不打卡**：一直生效，直到用户手动取消（「这门课永远不用签」）。
 * 用一个开关无法同时表达，用两个开关则一眼可辨。
 *
 * ## 为什么列表只列「今日课程」
 * 应用手上只有今日课表（全量课表要额外按周扫描，代价大且与本页无关）。
 * 这不影响长期设置的可用性：排除键是**课程级**的（`courseId`），
 * 今天为某门课打开「长期不打卡」，它在此后每一天都不会被自动签。
 */
@Composable
private fun AutoSignExcludeCard(state: AppUiState, vm: MainViewModel) {
    var showPicker by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("不自动打卡的课程", fontWeight = FontWeight.Bold)
            Text(
                "「今日跳过」只在今天生效；「长期不打卡」一直生效，随时可以取消。",
                fontSize = 12.sp,
                color = Color(0xFF5B6B63),
            )
            Text(
                "今日跳过 ${state.excludedToday.size} 节 · 长期排除 ${state.excludedPermanent.size} 门",
                fontSize = 12.sp,
                color = Color(0xFF0F6B4C),
                fontWeight = FontWeight.Medium,
            )
            if (state.courses.isEmpty()) {
                Text(
                    "今日课程尚未加载：请先在首页点「刷新课程」，再来选择。",
                    fontSize = 11.sp,
                    color = Color(0xFFB26A00),
                )
            }
            if (!state.settings.autoSignEnabled) {
                Text(
                    "自动签到当前是关闭状态，这里的设置会在开启后生效。",
                    fontSize = 11.sp,
                    color = Color(0xFF6B7C74),
                )
            }
            OutlinedButton(
                onClick = { showPicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("选择课程") }
        }
    }
    if (showPicker) {
        ExcludeCourseDialog(state, vm) { showPicker = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExcludeCourseDialog(
    state: AppUiState,
    vm: MainViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择不自动打卡的课程") },
        text = {
            if (state.courses.isEmpty()) {
                Text("今日暂无课程。请先在首页刷新课程后再设置。", fontSize = 13.sp)
            } else {
                LazyColumn(
                    // 上限而不是固定高度：课程少时弹窗紧凑，课程多时内部滚动，
                    // 不会把「完成」按钮顶出屏幕。
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.courses, key = { it.id + it.beginTime }) { course ->
                        ExcludeCourseRow(course, state, vm)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExcludeCourseRow(course: Course, state: AppUiState, vm: MainViewModel) {
    // 键的计算要跟着课程对象走：课表刷新后同一个 list 位置可能是另一门课，
    // 若用 remember{} 缓存而不给 key，会把上一门课的排除状态显示到新课上。
    val key = remember(course) { AutoSignExclusions.courseKey(course) }
    val todayExcluded = key in state.excludedToday
    val permanent = key in state.excludedPermanent
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "${CourseTimeDisplay.range(course.beginTime, course.endTime)} · ${course.name}",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF16362B),
            maxLines = 2,
        )
        Text(
            "教师：${course.teacher.ifBlank { "—" }}",
            fontSize = 11.sp,
            color = Color(0xFF5B6B63),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = todayExcluded,
                onClick = { vm.setExcludeToday(course, !todayExcluded) },
                label = { Text("今日跳过", fontSize = 12.sp) },
            )
            FilterChip(
                selected = permanent,
                onClick = { vm.setExcludePermanent(course, !permanent) },
                label = { Text("长期不打卡", fontSize = 12.sp) },
            )
        }
    }
}

@Composable
private fun KeepAliveCard(state: AppUiState, vm: MainViewModel) {
    val activity = LocalContext.current as? ComponentActivity
    val ka = state.keepAlive
    var showLockGuide by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.keepalive_title), fontWeight = FontWeight.Bold)

            if (ka.autoSignEnabled && !ka.batteryExempt) {
                Text(
                    stringResource(R.string.keepalive_alert_no_battery),
                    fontSize = 12.sp,
                    color = Color(0xFFB42318),
                    fontWeight = FontWeight.Medium,
                )
            }
            if (ka.autoSignEnabled && !ka.notificationsAllowed) {
                Text(
                    stringResource(R.string.keepalive_alert_no_notification),
                    fontSize = 12.sp,
                    color = Color(0xFFB42318),
                    fontWeight = FontWeight.Medium,
                )
            }
            if (ka.daemonBlocked) {
                Text(
                    stringResource(R.string.keepalive_alert_daemon_blocked),
                    fontSize = 12.sp,
                    color = Color(0xFFB42318),
                    fontWeight = FontWeight.Medium,
                )
            }

            Text(stringResource(R.string.keepalive_status_title), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            StatusRow(stringResource(R.string.keepalive_status_battery), ka.batteryExempt)
            StatusRow(stringResource(R.string.keepalive_status_notification), ka.notificationsAllowed)
            StatusRow(stringResource(R.string.keepalive_status_exact_alarm), ka.exactAlarmAllowed)
            StatusRow(
                stringResource(R.string.keepalive_status_autostart),
                ka.autoStartConfirmed,
                unknown = !ka.autoStartConfirmed,
            )
            StatusRow(
                stringResource(R.string.keepalive_status_lock),
                ka.lockConfirmed,
                unknown = !ka.lockConfirmed,
            )
            if (!ka.lowPowerMode) {
                StatusRow(
                    stringResource(R.string.keepalive_status_daemon),
                    ka.daemonRunning && !ka.daemonBlocked,
                )
            }
            if (!ka.autoStartConfirmed) {
                Text(
                    stringResource(R.string.keepalive_autostart_note),
                    fontSize = 11.sp,
                    color = Color(0xFF6B7C74),
                )
            }

            OutlinedButton(
                onClick = { activity?.let { KeepAliveHelper.requestBatteryExemption(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.keepalive_open_battery)) }
            OutlinedButton(
                onClick = { activity?.let { KeepAliveHelper.openAutoStart(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.keepalive_open_autostart)) }
            if (!ka.exactAlarmAllowed) {
                OutlinedButton(
                    onClick = { activity?.let { KeepAliveHelper.openExactAlarmSettings(it) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.keepalive_open_exact_alarm)) }
            }
            if (!ka.notificationsAllowed) {
                OutlinedButton(
                    onClick = { activity?.let { KeepAliveHelper.openNotificationSettings(it) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.keepalive_open_notification)) }
            }
            OutlinedButton(
                onClick = { showLockGuide = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.keepalive_lock_button)) }
        }
    }
    if (showLockGuide) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showLockGuide = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.setLockConfirmed(true)
                        showLockGuide = false
                    },
                ) { Text(stringResource(R.string.keepalive_lock_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showLockGuide = false }) {
                    Text(stringResource(R.string.keepalive_lock_close))
                }
            },
            title = { Text(stringResource(R.string.keepalive_lock_title)) },
            text = { Text(KeepAliveHelper.lockInRecentsGuide(context), fontSize = 12.sp) },
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, unknown: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFF3E5249))
        Text(
            when {
                unknown -> stringResource(R.string.keepalive_status_unknown)
                ok -> stringResource(R.string.keepalive_status_ok)
                else -> stringResource(R.string.keepalive_status_todo)
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                unknown -> Color(0xFF8FA89C)
                ok -> Color(0xFF2E7D4F)
                else -> Color(0xFFB42318)
            },
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun QrCard(state: AppUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("签到二维码时间轴", fontWeight = FontWeight.Bold)
            Text(
                "当前二维码课程：${state.selected?.name ?: "未选择"}",
                color = Color(0xFF0F6B4C),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            state.selected?.let {
                Text(
                    "${CourseTimeDisplay.range(it.beginTime, it.endTime)} · ${it.teacher.ifBlank { "教师未知" }}",
                    fontSize = 12.sp,
                    color = Color(0xFF5B6B63),
                )
            }
            val qr = state.qr
            if (qr == null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        state.selected == null && state.courses.isEmpty() -> "暂无课程，刷新后显示二维码"
                        state.selected == null -> "点选下方课程，或等待默认当前课"
                        state.error.isNotBlank() -> "二维码同步失败，正在重试…"
                        else -> "正在同步学校时间轴…"
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF5B6B63),
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            } else {
                val bmp = remember(qr.url) { encodeQr(qr.url) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "QR",
                        modifier = Modifier.size(180.dp),
                    )
                }
                Text(
                    "二维码有效  剩余：${"%.1f".format(state.qrRemaining)}s",
                    fontWeight = FontWeight.Medium,
                )
                LinearProgressIndicator(
                    progress = { state.qrProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "━━━━━━●━━━━  跟随后台时间轴",
                    fontSize = 11.sp,
                    color = Color(0xFF6B7C74),
                )
                if (state.selected?.signed == true) {
                    Text(
                        "该课已签到，仍显示二维码供核对",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7C74),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CourseRow(
    course: Course,
    selected: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val accent = when {
        course.signed -> Color(0xFF2E7D4F)
        isCurrent -> Color(0xFF0F6B4C)
        selected -> Color(0xFF3D8B6E)
        else -> Color(0xFF8FA89C)
    }
    val bg = when {
        selected -> Color(0xFFE7F4EE)
        isCurrent -> Color(0xFFF3FAF6)
        else -> Color.White
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (selected || isCurrent) 1.5.dp else 0.dp,
                color = if (selected || isCurrent) accent.copy(alpha = 0.35f) else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            ),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 0.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .background(accent, RoundedCornerShape(999.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        CourseTimeDisplay.range(course.beginTime, course.endTime),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF3E5249),
                    )
                    Text(
                        when {
                            course.signed -> "已签到"
                            selected && !isCurrent -> "查看二维码"
                            selected -> "二维码"
                            isCurrent -> "当前"
                            else -> "未签到"
                        },
                        fontSize = 11.sp,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    course.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color(0xFF16362B),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "教师：${course.teacher.ifBlank { "—" }}",
                    fontSize = 13.sp,
                    color = Color(0xFF5B6B63),
                )
            }
        }
    }
}

private fun statusLabel(status: AttendanceUiStatus): String = when (status) {
    AttendanceUiStatus.NOT_STARTED -> "未开始"
    AttendanceUiStatus.WAITING -> "等待签到"
    AttendanceUiStatus.READY -> "可签到"
    AttendanceUiStatus.SIGNING -> "签到中"
    AttendanceUiStatus.SIGNED -> "已签到"
    AttendanceUiStatus.FAILED -> "失败"
    AttendanceUiStatus.QR_EXPIRED -> "QR已过期"
    AttendanceUiStatus.LOGIN_EXPIRED -> "登录失效"
}

private fun encodeQr(content: String): Bitmap? = try {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp.setPixel(x, y, if (matrix[x, y]) 0xFF0B3D2E.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    bmp
} catch (_: Exception) {
    null
}
