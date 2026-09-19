package com.ucas.qingxin.signin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 徽章底色：浅琥珀，在白色卡片与浅绿背景上都够醒目，又不至于像报错。 */
private val BADGE_BG = Color(0xFFFFF1D6)
private val BADGE_FG = Color(0xFF8A5300)

/**
 * 「测试中」标记。
 *
 * ## 为什么要把它做成一枚徽章，而不是写进说明文字
 * 讲座相关功能的现状是「**能用，但判据还在变**」：数据源分两类（需登录的预约系统 /
 * 免登录的学院网站），已结束的判据按来源分开，还有一条 14 天的兜底启发式。
 * 这些规则在后续版本里很可能调整，甚至某一条会整体废弃。
 *
 * 只把这句话写进说明段落，用户是读不到的 —— 那里已经有三段关于数据来源的文字。
 * 徽章贴在标题旁边，才能保证「看到内容的同时看到它的成熟度」。
 *
 * 共享给讲座页与讲座预告页使用，避免两处颜色 / 字号漂移。
 */
@Composable
internal fun TestBadge(modifier: Modifier = Modifier) {
    Text(
        text = "测试中",
        modifier = modifier
            .background(BADGE_BG, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = BADGE_FG,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
