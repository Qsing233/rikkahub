package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.Temporal
import java.util.Locale
import java.util.TimeZone

data class PlaceholderCtx(
    val context: Context,
    val settingsStore: SettingsStore,
    val model: Model,
    val assistant: Assistant,
)

interface PlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo>
}

data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
    val resolver: (PlaceholderCtx) -> String
)

class PlaceholderBuilder {
    private val placeholders = mutableMapOf<String, PlaceholderInfo>()

    fun placeholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver)
    }

    fun build(): Map<String, PlaceholderInfo> = placeholders.toMap()
}

fun buildPlaceholders(block: PlaceholderBuilder.() -> Unit): Map<String, PlaceholderInfo> {
    return PlaceholderBuilder().apply(block).build()
}

object DefaultPlaceholderProvider : PlaceholderProvider {
    override val placeholders: Map<String, PlaceholderInfo> = buildPlaceholders {
        placeholder("cur_date", { Text(stringResource(R.string.placeholder_current_date)) }) {
            LocalDate.now().toDateString()
        }

        placeholder("cur_time", { Text(stringResource(R.string.placeholder_current_time)) }) {
            LocalTime.now().toTimeString()
        }

        placeholder("cur_datetime", { Text(stringResource(R.string.placeholder_current_datetime)) }) {
            LocalDateTime.now().toDateTimeString()
        }

        placeholder("model_id", { Text(stringResource(R.string.placeholder_model_id)) }) {
            it.model.modelId
        }

        placeholder("model_name", { Text(stringResource(R.string.placeholder_model_name)) }) {
            it.model.displayName
        }

        placeholder("locale", { Text(stringResource(R.string.placeholder_locale)) }) {
            Locale.getDefault().displayName
        }

        placeholder("timezone", { Text(stringResource(R.string.placeholder_timezone)) }) {
            TimeZone.getDefault().displayName
        }

        placeholder("system_version", { Text(stringResource(R.string.placeholder_system_version)) }) {
            "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
        }

        placeholder("device_info", { Text(stringResource(R.string.placeholder_device_info)) }) {
            "${Build.BRAND} ${Build.MODEL}"
        }

        placeholder("battery_level", { Text(stringResource(R.string.placeholder_battery_level)) }) {
            it.context.batteryLevel().toString()
        }

        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }

        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) {
            it.assistant.name.ifBlank { "assistant" }
        }

        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }

       placeholder("step_count", { Text(stringResource(R.string.placeholder_step_count)) }) {
            it.context.getStepCount()
        }

        placeholder("screen_time", { Text(stringResource(R.string.placeholder_screen_time)) }) {
            it.context.getScreenTime()
        }

        placeholder("battery_status", { Text(stringResource(R.string.placeholder_battery_status)) }) {
            it.context.getBatteryStatus()
        }
    }

    private fun Temporal.toDateString() = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Temporal.toTimeString() = DateTimeFormatter
        .ofLocalizedTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Temporal.toDateTimeString() = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Context.batteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {
    private val defaultProvider = DefaultPlaceholderProvider

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settingsStore = get<SettingsStore>()
        return messages.map {
            it.copy(
                parts = it.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        part.copy(
                            text = replacePlaceholders(text = part.text, ctx = ctx, settingsStore = settingsStore)
                        )
                    } else {
                        part
                    }
                }
            )
        }
    }

    private fun replacePlaceholders(
        text: String,
        ctx: TransformerContext,
        settingsStore: SettingsStore
    ): String {
        var result = text

        val ctx = PlaceholderCtx(
            context = ctx.context,
            settingsStore = settingsStore,
            model = ctx.model,
            assistant = ctx.assistant
        )
        defaultProvider.placeholders.forEach { (key, placeholderInfo) ->
            val value = placeholderInfo.resolver(ctx)
            result = result
                .replace(oldValue = "{{$key}}", newValue = value, ignoreCase = true)
                .replace(oldValue = "{$key}", newValue = value, ignoreCase = true)
        }

        return result
    }
}

    private fun Context.getStepCount(): String {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        return if (stepSensor != null) {
            "当前设备支持计步，需在系统设置开启运动健身权限后获取实时数据"
        } else {
            "当前设备不支持计步传感器"
        }
    }

   private fun Context.getScreenTime(): String {
    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return "❌ 无法获取屏幕时长（设备不支持 UsageStats）"
    val currentTime = System.currentTimeMillis()
    val startOfDay = getStartOfDayMillis()
    val hasPermission = usageStatsManager.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY, startOfDay, currentTime
    ).isNotEmpty()
    if (!hasPermission) {
        return "⚠️ 请开启权限：设置 → 应用 → 特殊权限 → 使用情况访问权限 → RikkaHub"
    }
    val events = usageStatsManager.queryEvents(startOfDay, currentTime)
    val event = UsageEvents.Event()
    val activeCount = mutableMapOf<String, Int>()
    val foregroundStart = mutableMapOf<String, Long>()
    val appForegroundTime = mutableMapOf<String, Long>()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val pkg = event.packageName
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                val count = activeCount.getOrDefault(pkg, 0)
                if (count == 0) {
                    foregroundStart[pkg] = event.timeStamp
                }
                activeCount[pkg] = count + 1
            }
            UsageEvents.Event.ACTIVITY_PAUSED -> {
                val count = activeCount.getOrDefault(pkg, 0)
                if (count <= 1) {
                    activeCount[pkg] = 0
                    val start = foregroundStart.remove(pkg)
                    if (start != null) {
                        appForegroundTime[pkg] =
                            (appForegroundTime[pkg] ?: 0L) + (event.timeStamp - start)
                    }
                } else {
                    activeCount[pkg] = count - 1
                }
            }
        }
    }
    for ((pkg, start) in foregroundStart) {
        appForegroundTime[pkg] =
            (appForegroundTime[pkg] ?: 0L) + (currentTime - start)
    }
    val totalTime = appForegroundTime.values.sum()
    val hours = totalTime / 1000 / 3600
    val minutes = (totalTime / 1000 / 60) % 60
    val topApps = appForegroundTime.entries
        .filter { it.value > 60_000 }
        .sortedByDescending { it.value }
        .take(10)
        .map { (pkg, time) ->
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (e: Exception) {
                pkg.substringAfterLast(".")
            }
            val appMin = time / 1000 / 60
            "📱 $appName: ${appMin}分钟"
        }
    return buildString {
        appendLine("📱 今日屏幕时长: ${hours}小时${minutes}分钟")
        appendLine("📊 Top 应用使用情况：")
        if (topApps.isNotEmpty()) {
            append(topApps.joinToString("\n"))
        } else {
            append("  无详细数据（使用时长均<1分钟）")
        }
    }
}


    private fun Context.getBatteryStatus(): String {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }
        return "电量: ${level}% | 状态: ${statusText}"
    }

    private fun getStartOfDayMillis(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
