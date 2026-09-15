package com.ucas.qingxin.signin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ucas.qingxin.signin.R
import com.ucas.qingxin.signin.data.AttendanceUiStatus
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.util.CourseTimeDisplay
import com.ucas.qingxin.signin.widget.TodayCourseWidgetReceiver

private enum class AppScreen { Home, Settings }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        setContent {
            val state by vm.ui.collectAsStateWithLifecycle()
            var screen by remember { mutableStateOf(AppScreen.Home) }
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
                            else -> HomeScreen(
                                state = state,
                                vm = vm,
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
    onOpenSettings: () -> Unit,
) {
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
                    Text("学号 ${state.studentNo}", color = Color(0xFF4A5C55))
                }
                TextButton(onClick = onOpenSettings) { Text("设置") }
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

@Composable
private fun SettingsScreen(
    state: AppUiState,
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val activity = LocalContext.current as? ComponentActivity
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
                    SettingSwitch("自动签到", state.settings.autoSignEnabled) {
                        vm.updateSettings(autoSign = it)
                    }
                    SettingSwitch("签到通知", state.settings.notifyEnabled) {
                        vm.updateSettings(notify = it)
                    }
                }
            }
        }
        item {
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
                        "支持主流 Android 启动器（含小米 HyperOS 负一屏）。规格：2×2 / 4×2 / 4×4。",
                        fontSize = 12.sp,
                        color = Color(0xFF5B6B63),
                    )
                    OutlinedButton(
                        onClick = { activity?.let { TodayCourseWidgetReceiver.requestPin(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.widget_pin)) }
                    OutlinedButton(
                        onClick = {
                            activity?.let {
                                TodayCourseWidgetReceiver.openAppDetailsForShortcutPermission(it)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.widget_pin_open_permission)) }
                }
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
