package com.ucas.qingxin.signin.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ucas.qingxin.signin.lecture.LectureEventKind
import com.ucas.qingxin.signin.lecture.LecturePortal
import com.ucas.qingxin.signin.lecture.LectureSessionBootstrap
import com.ucas.qingxin.signin.lecture.LectureTableParser

private val ACCENT = Color(0xFF0F6B4C)
private val TEXT_SUB = Color(0xFF5B6B63)

/**
 * 「讲座预告（预约系统）」—— 内嵌 WebView。
 *
 * 会话建立**完整移植**上游 UCAS-Desktop 的 `establishLectureSession`：
 * 1. SEP 登录（用户亲自完成验证码/邮箱验证）
 * 2. 在工作台找「选课系统 / 人文讲座报名」门户入口
 * 3. 进入选课主页后，在隐藏菜单里找「讲座预告」桥接
 * 4. 落到 xkcts 栏目页且表头含「讲座时间 / 讲座名称」后再读表
 *
 * **禁止**在未建会话时 `loadUrl(栏目地址)` —— 那正是 401 JSON 的来源。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LectureScheduleScreen(
    state: LectureScheduleUiState,
    vm: LectureScheduleViewModel,
    onBack: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }

    val sepLogin = vm.sepLoginUrl
    val xkctsBase = vm.xkctsBaseUrl
    val humanity = vm.humanityUrl
    val science = vm.scienceUrl

    val applyStep: (WebView, LectureSessionBootstrap.Step) -> Unit = remember(vm) {
        { view, step -> dispatchStep(view, step, vm) }
    }

    val read: (WebView) -> Unit = remember(humanity, science) {
        { view ->
            val resolved = LectureScheduleViewModel.kindOf(view.url.orEmpty(), humanity, science)
            if (resolved == null) {
                vm.onReadFailed("尚未停在讲座列表页。请点「人文讲座」经选课系统进入。")
            } else {
                vm.onReading()
                view.evaluateJavascript(LectureTableParser.EXTRACT_SCRIPT) { result ->
                    vm.onExtracted(result, resolved)
                }
            }
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "讲座预告（预约系统）",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TestBadge()
            }
        }
        ScheduleStatusCard(
            state = state,
            vm = vm,
            onRead = { webView?.let(read) },
            onOpenHumanity = {
                webView?.let { view ->
                    applyStep(view, vm.beginOpenSchedule(LectureEventKind.HUMANITY, view.url.orEmpty()))
                }
            },
            onOpenScience = {
                webView?.let { view ->
                    applyStep(view, vm.beginOpenSchedule(LectureEventKind.SCIENCE, view.url.orEmpty()))
                }
            },
        )
        if (state.bootstrapHint.isNotBlank()) {
            Text(state.bootstrapHint, fontSize = 13.sp, color = ACCENT, fontWeight = FontWeight.Medium)
        }
        if (state.message.isNotBlank()) {
            Text(state.message, fontSize = 13.sp, color = ACCENT, fontWeight = FontWeight.Medium)
        }
        if (state.error.isNotBlank()) {
            Text(state.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column {
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            configureForLogin(this)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    vm.onPageStarted()
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val view2 = view ?: return
                                    val target = url.orEmpty()

                                    // 裸 401 JSON：拉回 SEP
                                    view2.evaluateJavascript(BODY_TEXT_SCRIPT) { raw ->
                                        val body = unquoteJsString(raw)
                                        if (LectureScheduleViewModel.isAuthFailurePayload(body)) {
                                            vm.onReadFailed("登录页返回了未登录提示，已重新打开 SEP。")
                                            humanUrlOrNull(sepLogin)?.let { view2.loadUrl(it) }
                                            return@evaluateJavascript
                                        }

                                        val surface = LectureScheduleViewModel.surfaceOf(
                                            target,
                                            humanity,
                                            sepLogin,
                                            vm.xkgoMainUrl,
                                            xkctsBase,
                                        )
                                        val onSchedule = surface == LecturePortal.Surface.SCHEDULE
                                        vm.onPageFinished(
                                            pageTitle = when (surface) {
                                                LecturePortal.Surface.SCHEDULE -> "讲座列表"
                                                LecturePortal.Surface.XKGO_MAIN -> "选课系统"
                                                LecturePortal.Surface.SEP_WORKBENCH -> "SEP 工作台"
                                                LecturePortal.Surface.SEP_LOGIN -> "SEP 登录"
                                                else -> "登录 / 跳转"
                                            },
                                            onSchedulePage = onSchedule,
                                        )

                                        // 推进会话状态机（可能导航或执行查找脚本）
                                        applyStep(view2, vm.onBootstrapPageFinished(target))

                                        // 已在列表且未在 bootstrap：延迟读表
                                        if (onSchedule && !vm.ui.value.bootstrapping) {
                                            view2.postDelayed({ read(view2) }, READ_DELAY_MS)
                                        }
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    vm.onPageError("页面加载失败，请检查网络后重试。")
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            webView = this
                            humanUrlOrNull(sepLogin)?.let { loadUrl(it) }
                        }
                    },
                )
            }
        }
        Text(
            "流程与桌面端一致：先 SEP 登录 → 自动经「选课系统」进入「讲座预告」→ 读取时间表。" +
                "请先完成登录（含验证码），再点「人文讲座」。应用不保存账号密码。",
            fontSize = 11.sp,
            color = TEXT_SUB,
            lineHeight = 15.sp,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
            webView = null
        }
    }
}

private fun dispatchStep(
    view: WebView,
    step: LectureSessionBootstrap.Step,
    vm: LectureScheduleViewModel,
) {
    when (step) {
        is LectureSessionBootstrap.Step.Navigate -> view.loadUrl(step.url)
        is LectureSessionBootstrap.Step.Evaluate -> {
            view.evaluateJavascript(step.script) { raw ->
                val next = vm.onBootstrapScriptResult(step.expect, raw, view.url.orEmpty())
                dispatchStep(view, next, vm)
            }
        }
        is LectureSessionBootstrap.Step.ReadSchedule -> {
            vm.onReading()
            view.evaluateJavascript(LectureTableParser.EXTRACT_SCRIPT) { result ->
                vm.onExtracted(result, step.kind)
            }
        }
        is LectureSessionBootstrap.Step.Fail -> Unit // 已在 ViewModel 写入 error
        LectureSessionBootstrap.Step.Wait -> Unit
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureForLogin(view: WebView) {
    with(view.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadsImagesAutomatically = true
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
        // 与桌面端一致用桌面 Chrome UA，避免校内页对移动端走 API 401。
        userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    WebView.setWebContentsDebuggingEnabled(false)
}

private const val READ_DELAY_MS = 800L
private const val BODY_TEXT_SCRIPT =
    "(function(){try{return (document.body&&document.body.innerText)||'';}catch(e){return '';}})()"

private fun unquoteJsString(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
        return text.substring(1, text.length - 1)
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
    return text
}

private fun humanUrlOrNull(url: String): String? = url.trim().takeIf { it.isNotEmpty() }

@Composable
private fun ScheduleStatusCard(
    state: LectureScheduleUiState,
    vm: LectureScheduleViewModel,
    onRead: () -> Unit,
    onOpenHumanity: () -> Unit,
    onOpenScience: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    state.pageLoading || state.bootstrapping -> state.bootstrapHint.ifBlank { "处理中…" }
                    state.onSchedulePage -> "已停在讲座列表页"
                    else -> "请先完成 SEP 登录，再点下方按钮"
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                if (state.readAt.isBlank()) {
                    "尚未读取过讲座列表。"
                } else {
                    "上次读取：${state.readAt} · 共 ${state.eventCount} 场" +
                        if (state.kinds.isEmpty()) "" else
                            "（${state.kinds.joinToString("、") { it.label }}）"
                },
                fontSize = 12.sp,
                color = TEXT_SUB,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenHumanity,
                    enabled = !state.reading && !state.bootstrapping,
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT),
                ) { Text("人文讲座", fontSize = 14.sp) }
                Button(
                    onClick = onOpenScience,
                    enabled = !state.reading && !state.bootstrapping,
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT),
                ) { Text("科学前沿", fontSize = 14.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onRead,
                    enabled = !state.reading && !state.pageLoading && !state.bootstrapping,
                ) { Text(if (state.reading) "读取中…" else "重新读取") }
                TextButton(
                    onClick = vm::clearStore,
                    enabled = !state.reading && !state.empty,
                ) { Text("清空数据") }
            }
        }
    }
}
