package com.ucas.qingxin.signin.attendance

import android.content.Context
import android.os.PowerManager
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.data.UserSettings
import java.util.concurrent.TimeUnit

/**
 * 耗电档位：把散落在各处的刷新间隔收成**唯一**一处。
 *
 * ## 为什么需要它
 *
 * 在 1.2.1 之前，用户设置里的「低耗电模式」只影响后台签到通道
 * （[AttendanceScheduler.start] 里停掉守护服务、[AutoSignAlarmReceiver] 里不再拉起它），
 * 而 UI 循环、小部件、讲座巡检、更新检查**完全不看这个开关**。
 * 于是「省电模式」名不副实：用户开了它，小部件照样每 10 分钟唤醒一次。
 *
 * 现在所有取值方统一从 [PowerProfiles.resolve] 拿数值，档位才有意义。
 *
 * ## 两档的思路差别（而不是「大一点 / 小一点」）
 *
 * - [NORMAL]：**消除浪费，不改功能**。用事件驱动替代轮询、消除重复唤醒；
 *   用户可见行为与 1.2.0 一致。
 * - [SAVER]：**降低后台时序精度与唤醒总数**。主动放弃常驻服务、收紧窗口内重试预算、
 *   把周期性刷新周期翻倍。代价只有「偶发晚几十秒到十几分钟」。
 *
 * 两档都**不放宽窗口内取码间隔**：后端二维码 5 秒一帧，放宽只会扫到废码。
 * 二维码省电靠「只在主页可见且在签到窗口内才取」实现（见 `QrRefreshPolicy`），
 * 这是两档共用的一条规则。
 *
 * ## 不放进档位的东西
 *
 * `AppWidgetProviderInfo.updatePeriodMillis` 由宿主（桌面 / 负一屏）读取，
 * **无法在运行时按档位改变**，因此它固定为 [WIDGET_UPDATE_PERIOD_XML_MILLIS]，
 * 见 `res/xml/today_course_widget_*_info.xml`。省电档对此的补偿是：
 * 边界闹钟触发时不再抓网络、只做本地重绘。
 */
enum class PowerProfile(
    /** 是否允许常驻守护服务（前台服务 + 常驻通知）。 */
    val daemonEnabled: Boolean,
    /** 每个课次在签到窗口内允许的签到请求次数上限。 */
    val maxAttempts: Int,
    /** 小部件数据兜底闹钟的间隔（近期没有课时边界时的上限）。 */
    val widgetFallbackMs: Long,
    /** 守护服务存活时的数据兜底间隔（只有 [daemonEnabled] 的档位才用得到）。 */
    val widgetDaemonFallbackMs: Long,
    /** 小部件周期 WorkManager 的间隔。 */
    val widgetPeriodicMinutes: Long,
    /** 被唤起后小部件秒级刷新（ticker）的宽限时长；0 = 不使用 ticker。 */
    val widgetGraceMs: Long,
    /** 讲座巡检周期任务的间隔。 */
    val lectureCheckHours: Long,
    /** 更新检查的节流间隔。 */
    val updateCheckIntervalMs: Long,
) {
    NORMAL(
        daemonEnabled = true,
        maxAttempts = 8,
        widgetFallbackMs = 60L * MINUTE_MS,
        widgetDaemonFallbackMs = 60L * MINUTE_MS,
        widgetPeriodicMinutes = 60L,
        widgetGraceMs = MINUTE_MS,
        lectureCheckHours = 24L,
        updateCheckIntervalMs = 24L * HOUR_MS,
    ),

    SAVER(
        daemonEnabled = false,
        maxAttempts = 3,
        widgetFallbackMs = 120L * MINUTE_MS,
        widgetDaemonFallbackMs = 120L * MINUTE_MS,
        widgetPeriodicMinutes = 120L,
        // 关闭秒级重绘：省电档只在「课时语义变化的那一刻」重绘一次，
        // 代价是最多一分钟内小部件显示的是上一分钟的状态。
        widgetGraceMs = 0L,
        lectureCheckHours = 48L,
        updateCheckIntervalMs = 24L * HOUR_MS,
    ),
    ;

    companion object {
        /** 档位解析失败 / 应用未就绪时的保守取值。 */
        val DEFAULT: PowerProfile = NORMAL

        /**
         * 三个 provider 描述文件里 `updatePeriodMillis` 的固定值（2 小时）。
         *
         * 该字段由宿主读取，Android **不提供**运行时修改的手段，因此不随档位变化；
         * 放在这里是为了让「为什么它不参与档位」这件事有据可查，并被单测锁定。
         */
        const val WIDGET_UPDATE_PERIOD_XML_MILLIS = 2L * HOUR_MS
    }
}

/** 枚举常量在构造时 companion 还没初始化，因此这些换算必须放在文件级的顶层。 */
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60L * MINUTE_MS

/**
 * 档位 + 系统省电策略收紧后的最终取值。
 *
 * 之所以不把「系统省电模式」直接映射成 [PowerProfile.SAVER]：用户在系统里
 * 因为电量低而进入省电模式时，不该连「上课前 15 分钟提醒」与守护服务一起消失
 * （那是用户显式在应用内开启的能力）。系统省电只收紧**重试预算**与**秒级重绘**，
 * 其余跟随用户档位。
 */
class ResolvedPowerProfile private constructor(
    val profile: PowerProfile,
    val maxAttempts: Int,
    val widgetGraceMs: Long,
    /** 系统是否处于省电模式（用于设置页文案与单测断言）。 */
    val systemPowerSave: Boolean,
) {
    val daemonEnabled: Boolean get() = profile.daemonEnabled
    val widgetFallbackMs: Long get() = profile.widgetFallbackMs
    val widgetDaemonFallbackMs: Long get() = profile.widgetDaemonFallbackMs
    val widgetPeriodicMinutes: Long get() = profile.widgetPeriodicMinutes
    val lectureCheckHours: Long get() = profile.lectureCheckHours
    val updateCheckIntervalMs: Long get() = profile.updateCheckIntervalMs
    val saver: Boolean get() = profile == PowerProfile.SAVER

    companion object {
        internal fun of(
            profile: PowerProfile,
            systemPowerSave: Boolean,
        ): ResolvedPowerProfile {
            val attempts = if (systemPowerSave) {
                minOf(profile.maxAttempts, SYSTEM_POWER_SAVE_MAX_ATTEMPTS)
            } else {
                profile.maxAttempts
            }
            val grace = if (systemPowerSave) 0L else profile.widgetGraceMs
            return ResolvedPowerProfile(
                profile = profile,
                maxAttempts = attempts,
                widgetGraceMs = grace,
                systemPowerSave = systemPowerSave,
            )
        }

        /** 系统省电模式下收敛预算，避免做无用功（沿用 1.2.0 的语义）。 */
        internal const val SYSTEM_POWER_SAVE_MAX_ATTEMPTS = 3
    }
}

/** 档位解析：唯一的入口，所有取值方都必须经过它。 */
object PowerProfiles {

    /**
     * 从 [Context] 解析当前档位。
     *
     * 优先走容器（[com.ucas.qingxin.signin.QingxinApp.powerProfile]，会带上系统省电收紧）；
     * 容器未就绪时**直接读设置**而不是退回默认值 —— 小部件宿主会冷启动本进程，
     * 那时 `isReady` 可能仍是 false，退回「普通档」会让省电档用户拿到偏短的刷新间隔。
     * 读的是同一份 prefs 与同一组键，见 [AttendanceScheduler.readSettings]。
     */
    fun forContext(context: Context): ResolvedPowerProfile {
        val appContext = context.applicationContext
        val app = appContext as? QingxinApp
        if (app != null && app.isReady) return app.powerProfile()
        return resolve(
            settings = AttendanceScheduler.readSettings(appContext),
            systemPowerSave = isSystemPowerSave(appContext),
        )
    }

    /** 把用户设置与系统省电状态合成最终取值。 */
    fun resolve(settings: UserSettings, systemPowerSave: Boolean): ResolvedPowerProfile =
        ResolvedPowerProfile.of(
            profile = if (settings.lowPowerMode) PowerProfile.SAVER else PowerProfile.NORMAL,
            systemPowerSave = systemPowerSave,
        )

    /**
     * 系统是否处于省电模式。
     *
     * 读的是 `PowerManager.isPowerSaveMode`（用户在系统设置里开启省电，
     * 或电量很低时系统自动进入），**不是** `isDeviceIdleMode`：
     * 后者是 Doze 的深度空闲，日常几乎一直是 `false`，用它做判断毫无意义。
     */
    fun isSystemPowerSave(context: Context): Boolean = runCatching {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode == true
    }.getOrDefault(false)

    /** 计划任务(Duration) → 毫秒；仅用于把档位字段喂给 WorkManager 之类的 API。 */
    fun hoursToMillis(hours: Long): Long = TimeUnit.HOURS.toMillis(hours)
}
