package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.LYFCLAW_PUSH_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import org.koin.android.ext.android.inject

private const val TAG = "LyfclawPushSvc"

/**
 * 前台保活 Service，维持 lyfclaw SSE 后台推送订阅的进程不被系统冻结/杀死。
 *
 * 生命周期由 [LyfclawPushManager] 控制：lyfclaw 启用且用户开后台推送时 start，否则 stop。
 * 订阅逻辑本身在 [LyfclawPushManager]（AppScope 协程），本 Service 仅提供前台保活。
 *
 * Android 14+ dataSync 类型有约 6 分钟超时，[onTimeout] 中用 AlarmManager 延迟重启。
 */
class LyfclawPushService : Service() {
    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.LYFCLAW_PUSH_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.LYFCLAW_PUSH_STOP"

        const val NOTIFICATION_ID = 2006

        fun start(context: Context) {
            val intent = Intent(context, LyfclawPushService::class.java).setAction(ACTION_START)
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.e(TAG, "Unable to start lyfclaw push service", it)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, LyfclawPushService::class.java).setAction(ACTION_STOP))
            }
        }
    }

    private var isForeground = false
    private val appScope: AppScope by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> enterForeground()
            ACTION_STOP -> stopService()
            else -> stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type=$fgsType), scheduling restart")
        scheduleRestart()
        stopService()
    }

    private fun enterForeground() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            stopSelf()
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun scheduleRestart() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(this, LyfclawPushService::class.java).setAction(ACTION_START)
        val pendingIntent = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60_000,
            pendingIntent,
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, LYFCLAW_PUSH_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_rikkahub)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.lyfclaw_push_service_notification_text))
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}
