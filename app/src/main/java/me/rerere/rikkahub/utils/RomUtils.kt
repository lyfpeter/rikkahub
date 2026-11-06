package me.rerere.rikkahub.utils

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log

private const val TAG = "RomUtils"

/**
 * 厂商品牌检测。
 */
object RomBrand {
    val manufacturer: String get() = Build.MANUFACTURER.lowercase()

    val isMiui: Boolean get() = manufacturer in setOf("xiaomi", "redmi")
    val isHuawei: Boolean get() = manufacturer in setOf("huawei", "honor")
    val isOppo: Boolean get() = manufacturer in setOf("oppo")
    val isVivo: Boolean get() = manufacturer in setOf("vivo")
    val isSamsung: Boolean get() = manufacturer == "samsung"
    val isMeizu: Boolean get() = manufacturer == "meizu"
    val isOnePlus: Boolean get() = manufacturer == "oneplus"
    val isRealme: Boolean get() = manufacturer == "realme"

    /** 是否为国产 ROM（有自启动管理/后台限制）。 */
    val isChineseRom: Boolean
        get() = isMiui || isHuawei || isOppo || isVivo || isMeizu || isOnePlus || isRealme

    /** 用于显示的品牌名。 */
    val displayName: String
        get() = when {
            isMiui -> "MIUI / HyperOS"
            isHuawei -> "EMUI / HarmonyOS"
            isOppo -> "ColorOS"
            isVivo -> "OriginOS"
            isSamsung -> "One UI"
            isMeizu -> "Flyme"
            isOnePlus -> "OxygenOS / ColorOS"
            isRealme -> "Realme UI"
            else -> "Android"
        }
}

/**
 * 检查应用是否已加入电池优化白名单。
 */
fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(packageName)
}

/**
 * 请求加入电池优化白名单（标准 Android API，所有 ROM 通用）。
 */
fun Context.requestIgnoreBatteryOptimizations() {
    runCatching {
        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }.onFailure { Log.w(TAG, "requestIgnoreBatteryOptimizations failed: ${it.message}") }
}

/**
 * 跳转到电池优化设置列表页（允许用户手动管理所有应用的电池优化）。
 */
fun Context.openBatteryOptimizationSettings() {
    runCatching {
        val intent = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }.onFailure { Log.w(TAG, "openBatteryOptimizationSettings failed: ${it.message}") }
}

/**
 * 尝试跳转到厂商自启动管理页。
 *
 * 各 ROM 的 Intent Component 不固定，系统版本升级可能变化；
 * 全部用 [runCatching] 包裹，失败时返回 false。
 *
 * @return true 表示跳转成功
 */
fun Context.openAutoStartSettings(): Boolean {
    val intents = when {
        RomBrand.isMiui -> listOf(
            // MIUI 自启动管理
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )),
            // HyperOS 兜底
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            )),
        )
        RomBrand.isHuawei -> listOf(
            // EMUI / HarmonyOS 启动管理
            Intent().setComponent(ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )),
            // 旧版 EMUI
            Intent().setComponent(ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            )),
        )
        RomBrand.isOppo || RomBrand.isOnePlus || RomBrand.isRealme -> listOf(
            // ColorOS 自启动管理
            Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )),
            Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )),
            // OnePlus
            Intent().setComponent(ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainstart.view.ChainStartAppListActivity",
            )),
        )
        RomBrand.isVivo -> listOf(
            // vivo 自启动管理
            Intent().setComponent(ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )),
            Intent().setComponent(ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            )),
        )
        RomBrand.isMeizu -> listOf(
            // Flyme 自启动管理
            Intent().setComponent(ComponentName(
                "com.meizu.safe",
                "com.meizu.safe.security.SHOW_APPSEC",
            )),
        )
        RomBrand.isSamsung -> listOf(
            // One UI 电池优化
            Intent().setComponent(ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            )),
        )
        else -> emptyList()
    }

    for (intent in intents) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            startActivity(intent)
            return true
        }
    }
    Log.w(TAG, "openAutoStartSettings: no matching activity for ${RomBrand.displayName}")
    return false
}

/**
 * 跳转到应用详情页（兜底，让用户手动去权限/电池设置）。
 */
fun Context.openAppDetailSettings() {
    runCatching {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }.onFailure { Log.w(TAG, "openAppDetailSettings failed: ${it.message}") }
}
