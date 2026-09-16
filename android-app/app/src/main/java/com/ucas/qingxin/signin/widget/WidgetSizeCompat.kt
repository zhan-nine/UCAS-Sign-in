/*
 * This file is part of UCAS-Sign-in.
 *
 * 本文件包含改编自 **Breezy Weather** 的代码，因此**本文件整体按
 * GNU Lesser General Public License v3.0（LGPL-3.0）授权**，
 * 而非本仓库其余部分所使用的 AGPL-3.0。完整许可证文本见
 * 仓库根目录 LICENSES/LGPL-3.0.txt，版权与来源声明见 NOTICE。
 *
 * Adapted from:
 *   Breezy Weather — app/src/main/kotlin/org/breezyweather/remoteviews/common/WidgetSizeUtils.kt
 *   https://github.com/breezy-weather/breezy-weather
 *   Copyright (C) Breezy Weather contributors
 *   Licensed under the GNU Lesser General Public License v3.0
 *
 * 修改说明（依据 LGPL-3.0 第 2 条，须显著标注改动）：
 *   - 2026-09-16：由 Breezy Weather 的 `WidgetSizeUtils.initializeRemoteViews`
 *     改编而来。改写为 Kotlin `internal object`，回调参数由 `WidgetSize`
 *     换成本项目的 `WidgetDpSize`，去除了 Breezy Weather 特有的依赖，
 *     并补充了尺寸缺失时的兜底值与异常保护。
 *   - 本文件**不是** Breezy Weather 的官方发行版本，也与其项目无隶属关系。
 */

package com.ucas.qingxin.signin.widget

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import kotlin.math.roundToInt

/** 宿主给到的小部件尺寸（单位 dp）。 */
internal data class WidgetDpSize(val widthDp: Int, val heightDp: Int)

/**
 * 小部件尺寸自适应（LGPL-3.0，见文件头）。
 *
 * 不同启动器上报尺寸的方式差别很大：
 * - Android 12+ 部分启动器会在 `OPTION_APPWIDGET_SIZES` 里给出**所有**可能的
 *   单元格尺寸，此时为每个尺寸各渲染一套布局，宿主按用户实际拖出的格子挑选，
 *   既不会被拉伸变形，也不会因为高度不足而裁掉课程；
 * - 不支持该字段的启动器（文档明确说明这与启动器实现有关）回退为
 *   「横屏 / 竖屏」两套布局；
 * - 更老的版本只能给出单一（竖屏）布局。
 */
internal object WidgetSizeCompat {

    fun initializeRemoteViews(
        manager: AppWidgetManager,
        appWidgetId: Int,
        build: (size: WidgetDpSize) -> RemoteViews,
    ): RemoteViews {
        val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }
            .getOrNull() ?: Bundle()

        val sizes: ArrayList<SizeF>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                BundleCompat.getParcelableArrayList(
                    options,
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    SizeF::class.java,
                )
            }.getOrNull()
        } else {
            null
        }

        val portrait = build(portraitSize(options))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || sizes.isNullOrEmpty()) {
            return RemoteViews(build(landscapeSize(options)), portrait)
        }

        return runCatching {
            val perSize = LinkedHashMap<SizeF, RemoteViews>()
            sizes.forEach { sizeF ->
                perSize[sizeF] = build(
                    WidgetDpSize(
                        sizeF.width.roundToInt().coerceAtLeast(1),
                        sizeF.height.roundToInt().coerceAtLeast(1),
                    ),
                )
            }
            RemoteViews(perSize)
        }.getOrDefault(portrait)
    }

    /** 竖屏：窄而高。 */
    fun portraitSize(options: Bundle): WidgetDpSize = WidgetDpSize(
        firstPositive(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
            DEFAULT_WIDTH_DP,
        ),
        firstPositive(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
            DEFAULT_HEIGHT_DP,
        ),
    )

    /** 横屏：宽而矮。 */
    fun landscapeSize(options: Bundle): WidgetDpSize = WidgetDpSize(
        firstPositive(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            DEFAULT_LANDSCAPE_WIDTH_DP,
        ),
        firstPositive(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
            DEFAULT_LANDSCAPE_HEIGHT_DP,
        ),
    )

    private fun firstPositive(vararg values: Int): Int = values.firstOrNull { it > 0 } ?: 0

    private const val DEFAULT_WIDTH_DP = 250
    private const val DEFAULT_HEIGHT_DP = 110
    private const val DEFAULT_LANDSCAPE_WIDTH_DP = 320
    private const val DEFAULT_LANDSCAPE_HEIGHT_DP = 110
}
