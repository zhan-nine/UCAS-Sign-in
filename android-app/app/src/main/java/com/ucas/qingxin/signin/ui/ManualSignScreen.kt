package com.ucas.qingxin.signin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ACCENT = Color(0xFF0F6B4C)
private val ACCENT_SOFT = Color(0xFF2E8B6A)
private val TEXT_MAIN = Color(0xFF16362B)
private val TEXT_SUB = Color(0xFF5B6B63)
private val WARN_BG = Color(0xFFFFF6E5)

/**
 * 「手动打卡」独立页面。
 *
 * ## 为什么值得独立成页
 * 使用场景是「**站在班牌前，读到编号，立刻要打卡**」。这条路径上任何多余的一步
 * 都是负担：要先进讲座页、再展开一个折叠卡片，还得先等讲座通知抓完
 * ——而讲座通知**与这次打卡毫无关系**。所以本页：
 *
 * 1. 不加载任何列表数据，进页即可用（断网也能正常提示）；
 * 2. 自动聚焦输入框并弹出键盘，省掉一次点击；
 * 3. **边输边判定**格式，点了才被告知「格式不对」是没必要的往返。
 *
 * ## 编号从哪来
 * 签到通道与普通课程完全一致（`stu_scan_sign.action` 只认 7 位 `courseSchedId`
 * 或 32 位 `timeTableId`），因此**只需要当场读到的编码**，
 * 不需要先在本应用的任何列表里找到这门课 / 这场讲座。
 *
 * ## 文案要诚实的一点
 * 这里**不显示课程名**：签到接口不回传名称，而本地也没有与这个编号对应的数据，
 * 所以「打卡成功」只能确认服务端接受了这次签到，不能顺带告诉用户是哪一门课。
 */
@Composable
fun ManualSignScreen(
    state: ManualSignUiState,
    vm: ManualSignViewModel,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 进页自动对焦：这一页的存在意义就是「马上要打卡」，先点一下输入框纯属浪费。
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

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
                    "手动打卡",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("录入现场编号", fontWeight = FontWeight.SemiBold)
                    Text(
                        "班牌、教室屏幕或二维码里读到的编号",
                        fontSize = 12.sp,
                        color = TEXT_SUB,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = vm::onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        singleLine = true,
                        label = { Text("7 位编号 / 32 位标识") },
                        // 示例刻意用明显的假数字，避免被误当成可直接使用的真实编号。
                        placeholder = { Text("例如 1234567") },
                        // 编号只可能是数字与十六进制字母：ASCII 键盘能省掉输入法来回切换。
                        // 等宽字体让 32 位标识便于逐段核对，输错一位肉眼可辨。
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        // 键盘上的「完成」直接提交：单手在班牌前操作时很实用。
                        keyboardActions = KeyboardActions(onDone = {
                            keyboard?.hide()
                            vm.sign()
                        }),
                        isError = state.input.isNotBlank() && state.recognized == null,
                        supportingText = {
                            if (state.hint.isNotBlank()) {
                                Text(
                                    state.hint,
                                    fontSize = 12.sp,
                                    color = if (state.recognized != null) ACCENT_SOFT else
                                        MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                keyboard?.hide()
                                vm.sign()
                            },
                            enabled = state.canSubmit,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ACCENT,
                                disabledContainerColor = Color(0xFF9BB5AA),
                            ),
                        ) {
                            Text(if (state.signing) "打卡中…" else "打卡", fontSize = 14.sp)
                        }
                        TextButton(
                            onClick = vm::clear,
                            enabled = !state.signing && state.input.isNotBlank(),
                        ) { Text("清空") }
                    }
                }
            }
        }

        if (state.success.isNotBlank()) {
            item {
                ResultBanner(
                    text = state.success,
                    fg = ACCENT,
                    bg = Color(0xFFE7F4EE),
                )
            }
        }
        if (state.error.isNotBlank()) {
            item {
                ResultBanner(
                    text = state.error,
                    fg = MaterialTheme.colorScheme.error,
                    bg = WARN_BG,
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("说明", fontWeight = FontWeight.SemiBold)
                    Bullet("支持 7 位数字编号，或 32 位十六进制标识（带连字符也可以，会自动去掉）。")
                    Bullet("编号格式不合格时不会发出任何请求，避免把无意义的输入送去签到接口。")
                    Bullet("本页不显示课程名与时间：签到接口不回传这些信息，本地也没有对应数据。")
                    Bullet("绿字只代表服务端接受了这次签到；若同一节课重复打卡，结果以服务端为准。")
                }
            }
        }
    }
}

/** 一条结果横幅（成功 / 失败共用，只换配色）。 */
@Composable
private fun ResultBanner(text: String, fg: Color, bg: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text, color = fg, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

/** 说明条目。用 `·` 前缀而不是 `-`：中文正文里圆点更接近中文排版习惯。 */
@Composable
private fun Bullet(text: String) {
    Text("· $text", fontSize = 12.sp, color = TEXT_SUB, lineHeight = 18.sp)
}
