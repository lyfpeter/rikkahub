# 定时任务消息推送设计文档

## 问题背景

RikkaHub 通过 lyfclaw 后端的 APScheduler 引擎执行定时任务（提醒、agent_run），结果经
`DirectChannel.send_message()` 落库到 session DB 并发布到 in-process `session_stream_bus`，
再由 SSE endpoint `GET /api/app/sessions/{id}/stream` 实时推送到客户端。

当前推送链路存在三重断裂，导致用户收不到定时任务消息：

1. **SSE 订阅绑在 `viewModelScope`**：`ChatVM` 在 `init {}` 中启动 `subscribeLyfclawPush()`，
   页面关闭（`onCleared`）即取消，SSE 连接断开。
2. **无系统级通知**：推送消息到达客户端后没有任何本地通知机制，用户不知道有新消息。
3. **无进程保活/唤醒机制**：App 进程被系统杀死后，没有任何机制重新建立连接或接收通知。

### 现有推送链路

```
lyfclaw APScheduler 触发
    ↓
DirectChannel.send_message()
    ├── store.append_message(sid, "assistant", text)   ← 消息持久化到 session DB
    └── session_stream_bus.publish(sid, None)           ← 通知 in-process 订阅者
        ├── 有 SSE 订阅者 → 推送 snapshot/node_update
        └── 无 SSE 订阅者 → 信号丢弃（消息已在 DB 中，但客户端不知道）

RikkaHub 客户端：
    ChatVM.viewModelScope → subscribeLyfclawPush(convId)
    ├── 轮询 lyfclawSessionId（2s 间隔）
    ├── SSE 连接 + retryWhen 退避重连（max 30s）
    └── applyLyfclawPush → mergeLyfclawMessages（高水位去重）
```

### 关键约束

- 后端消息已持久化到 session DB，不会丢失——打开页面时 `snapshot` 帧会全量同步。
- 问题在于"用户不知道有新消息"，而非"消息丢了"。
- `mergeLyfclawMessages` 通过 `lyfclawSyncedAt` 高水位去重，**多路订阅幂等安全**。
- 现有两个前台 Service 使用 `dataSync` 类型，Android 14+ 有约 6 分钟超时限制。
- 后端无 FCM / APNS 或其他系统级推送基础设施，需新建。
- `session_stream_bus` 是纯内存 in-process pub/sub，无持久化、无 replay。

---

## 设计目标

| 目标 | 说明 |
|---|---|
| 进程存活时实时送达 | 不在聊天页也能收到推送并弹通知 |
| 进程被杀后可恢复 | App 重新打开时自动同步积攒消息；后续可选 FCM 唤醒 |
| 复用现有架构 | 不推翻重写，复用 SSE 订阅、merge 去重、通知基础设施 |
| 渐进式实施 | 分阶段交付，每阶段独立可用 |

---

## 阶段一：AppScope 常驻订阅 + 前台 Service 保活

不需要改后端，纯客户端改动。

### 1. 方案概览

```
lyfclaw 启用 + 用户开启后台推送开关
    ↓
LyfclawPushService (前台 Service, dataSync)
    ├── AppScope 启动常驻 SSE 订阅
    │   └── 为每个有 lyfclawSessionId 的会话维护 SSE 连接
    ├── 收到消息 → mergeLyfclawMessages (幂等) + 系统通知
    └── 进程存活期间持续运行
```

### 2. 新增组件

#### 2.1 `LyfclawPushService` — 前台保活 Service

参照现有 `ChatGenerationForegroundService` / `MemoryDreamForegroundService` 的模式。

**职责**：维持前台 Service 状态，防止进程在后台被系统冻结/杀死，保障 SSE 连接存活。

**生命周期**：由 lyfclaw 状态和用户开关控制（不像现有两个 Service 是按需 acquire/release）。

```kotlin
// service/LyfclawPushService.kt — 核心结构
class LyfclawPushService : Service() {
    companion object {
        const val NOTIFICATION_ID = 2006
        const val CHANNEL_ID = "lyfclaw_push"
        const val ACTION_START = "me.rerere.rikkahub.action.START_PUSH"
        const val ACTION_STOP = "me.rerere.rikkahub.action.STOP_PUSH"

        fun start(context: Context) {
            val intent = Intent(context, LyfclawPushService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LyfclawPushService::class.java)
                .setAction(ACTION_STOP))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                enterForeground()
                // 订阅逻辑由 LyfclawPushManager 管理
            }
            ACTION_STOP -> {
                serviceScope.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY  // 被杀后尝试重启
    }

    private fun enterForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

**通知**：常驻通知栏，低优先级，提示"后台服务运行中"。

**AndroidManifest 注册**：

```xml
<service
    android:name=".service.LyfclawPushService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

**Android 14+ 超时处理**：

`dataSync` 类型在 Android 14+ 有约 6 分钟超时。处理策略：

```kotlin
override fun onTimeout(startId: Int) {
    // 用 AlarmManager 在短延迟后重启 Service
    scheduleRestart(this)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
}

private fun scheduleRestart(context: Context) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val intent = Intent(context, LyfclawPushService::class.java).setAction(ACTION_START)
    val pendingIntent = PendingIntent.getService(
        context, 0, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    // 1 分钟后重启
    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + 60_000,
        pendingIntent
    )
}
```

#### 2.2 `LyfclawPushManager` — 订阅管理器

**职责**：管理多个会话的 SSE 订阅，从 DB 加载有 `lyfclawSessionId` 的会话，维护订阅生命周期。

```kotlin
// service/LyfclawPushManager.kt
class LyfclawPushManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val lazyChatService: Lazy<ChatService>,
    private val appEventBus: AppEventBus,
    private val context: Application,
) {
    private val chatService by lazyChatService
    private val subscriptions = mutableMapOf<Uuid, Job>()  // conversationId -> 订阅协程
    private val mutex = Mutex()

    // 进程启动时调用 (createdAtStart in DI)
    fun start() {
        appScope.launch {
            val settings = settingsStore.awaitLoaded()
            if (!settings.isLyfclawEnabled || !settings.lyfclawBackgroundPush) return@launch

            LyfclawPushService.start(context)

            // 监听设置变化，开关关闭时停止
            settingsStore.settingsFlow
                .map { it.isLyfclawEnabled && it.lyfclawBackgroundPush }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) refreshSubscriptions()
                    else stopAll()
                }
        }
    }

    // 从 DB 扫描所有有 lyfclawSessionId 的会话，逐个启动订阅
    private suspend fun refreshSubscriptions() {
        val conversations = conversationRepo.getConversationsWithLyfclawSession()
        mutex.withLock {
            // 移除已不需要的订阅
            subscriptions.keys.filter { it !in conversations.map { c -> c.id } }
                .forEach { removeSubscription(it) }
            // 添加新订阅
            conversations.forEach { conv ->
                val sessionId = conv.config?.lyfclawSessionId ?: return@forEach
                if (conv.id !in subscriptions) {
                    addSubscription(conv.id, sessionId)
                }
            }
        }
    }

    private fun addSubscription(conversationId: Uuid, sessionId: String) {
        if (conversationId in subscriptions) return
        subscriptions[conversationId] = appScope.launch {
            chatService.subscribeLyfclawPushInBackground(conversationId, sessionId)
        }
    }

    private fun removeSubscription(conversationId: Uuid) {
        subscriptions.remove(conversationId)?.cancel()
    }

    private fun stopAll() {
        LyfclawPushService.stop(context)
        subscriptions.values.forEach { it.cancel() }
        subscriptions.clear()
    }

    // 会话获得新的 lyfclawSessionId 时调用
    fun onSessionIdUpdated(conversationId: Uuid, sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            removeSubscription(conversationId)
        } else {
            appScope.launch { addSubscription(conversationId, sessionId) }
        }
    }
}
```

**DI 注册**（`AppModule.kt`）：

```kotlin
single<LyfclawPushManager> {
    LyfclawPushManager(
        appScope = get(),
        settingsStore = get(),
        conversationRepo = get(),
        lazyChatService = lazy { get<ChatService>() },
        appEventBus = get(),
        context = androidContext(),
    )
}.createdAtStart()  // 进程启动即开始监听
```

#### 2.3 `ChatService` 改造

新增后台订阅方法，与现有 `subscribeLyfclawPush` 共享核心逻辑：

```kotlin
// ChatService.kt 新增

/**
 * 后台常驻订阅：由 LyfclawPushManager 调用，不依赖 viewModelScope。
 * 与 subscribeLyfclawPush 共享 SSE 订阅和 merge 逻辑，基于 lyfclawSyncedAt 幂等安全。
 */
suspend fun subscribeLyfclawPushInBackground(conversationId: Uuid, sessionId: String) {
    val svc = lyfclawChatService ?: return
    val wasInForeground = isAppInForeground()  // ProcessLifecycleOwner 检测

    svc.subscribeSession(sessionId)
        .retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            delay(reconnectDelayMillis(attempt))
            true
        }
        .collect { event ->
            applyLyfclawPush(conversationId, event)
            // 后台时发通知
            if (!isAppInForeground()) {
                maybeNotifyPush(conversationId, event)
            }
        }
}
```

**通知触发**：在收到 `Done` 事件时，如果 App 在后台，发送系统通知。

```kotlin
private fun maybeNotifyPush(conversationId: Uuid, event: LyfclawSseEvent) {
    when (event) {
        is LyfclawSseEvent.Done -> {
            val preview = event.text.take(100)
            if (preview.isNotBlank()) {
                appEventBus.tryEmit(AppEvent.LyfclawPushReceived(
                    conversationId = conversationId,
                    senderName = "lyfclaw",
                    contentPreview = preview,
                ))
            }
        }
        else -> Unit
    }
}
```

现有 `subscribeLyfclawPush`（ChatVM 调用的）保持不变，两者并行运行。`mergeLyfclawMessages`
基于 `lyfclawSyncedAt` 高水位去重，同一条消息不会被重复插入。

#### 2.4 通知渠道与事件

**新增通知渠道**（`RikkaHubApp.kt`）：

```kotlin
val lyfclawPushChannel = NotificationChannel(
    "lyfclaw_push",
    "定时任务推送",
    NotificationManager.IMPORTANCE_HIGH,  // 高优先级，有振动
).apply {
    description = "lyfclaw 定时任务结果到达时通知"
    enableVibration(true)
}
notificationManager.createNotificationChannel(lyfclawPushChannel)
```

**新增 AppEvent**（`AppEvent.kt`）：

```kotlin
sealed class AppEvent {
    // ... existing events ...
    data class LyfclawPushReceived(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String,
    ) : AppEvent()
}
```

**通知处理**（`ChatNotificationManager.kt` 中新增订阅）：

```kotlin
// 在 init {} 中新增
appScope.launch {
    appEventBus.collect { event ->
        when (event) {
            is AppEvent.LyfclawPushReceived -> {
                if (!isForeground.value) {
                    showLyfclawPushNotification(event)
                }
            }
            // ... existing handlers ...
        }
    }
}

private fun showLyfclawPushNotification(event: AppEvent.LyfclawPushReceived) {
    context.sendNotification(
        channelId = "lyfclaw_push",
        notificationId = event.conversationId.hashCode(),
        NotificationConfig(
            title = event.senderName,
            content = event.contentPreview,
            autoCancel = true,
            category = NotificationCompat.CATEGORY_MESSAGE,
            useBigTextStyle = true,
            contentIntent = conversationIntent(context, event.conversationId.toString()),
        )
    )
}
```

#### 2.5 设置开关

**`Settings.kt`** 新增字段：

```kotlin
/**
 * lyfclaw 后台推送：启用后常驻前台 Service 维持 SSE 连接，后台也能收到定时任务消息。
 * 需要 lyfclaw 已启用。
 */
val lyfclawBackgroundPush: Boolean = false,
```

**通知设置页**（`SettingPreferencesNotificationPage.kt`）新增开关：

```kotlin
// 仅在 isLyfclawEnabled 时显示
if (settings.isLyfclawEnabled) {
    SwitchPreference(
        title = "定时任务后台推送",
        subtitle = "常驻后台服务，接收定时任务消息并弹出通知",
        checked = settings.lyfclawBackgroundPush,
        onChanged = { value ->
            settingsStore.update { it.copy(lyfclawBackgroundPush = value) }
        },
    )
}
```

#### 2.6 数据流（阶段一完成后）

```
进程存活时：
  APScheduler → send_message → DB 落库 + bus publish
    → SSE 推送 → LyfclawPushService 收到
      ├── mergeLyfclawMessages (幂等)
      └── 后台时 → AppEvent.LyfclawPushReceived → 系统通知

  ChatVM (页面打开) → subscribeLyfclawPush
    → 同一 SSE 事件 → mergeLyfclawMessages (幂等，不会重复)

进程被系统杀死：
  无 SSE 连接，消息积压在 session DB
  下次打开 App → ChatVM → snapshot 全量同步
  (阶段二解决：FCM 推送通知)
```

### 3. 与现有机制的协调

| 现有机制 | 关系 |
|---|---|
| `subscribeLyfclawPush` (ChatVM) | 并行运行，merge 幂等安全。后续可优化为 ChatVM 不再自订阅，改由 AppEventBus 转发 |
| `ChatGenerationForegroundService` | 独立，不冲突。生成期间两个 Service 可以同时存在 |
| `MemoryDreamForegroundService` | 独立，不冲突 |
| `ChatNotificationManager` | 新增 `LyfclawPushReceived` 事件处理，复用同一通知出口 |
| `mergeLyfclawMessages` 高水位去重 | 核心安全保障，多路订阅不会重复插入消息 |
| `ConversationSession` 引用计数 | `LyfclawPushManager` 在订阅期间需 `addConversationReference`，防止会话被 idle 回收 |

### 4. 实施步骤

| 步骤 | 改动文件 | 说明 |
|---|---|---|
| 1a | `Settings.kt` | 添加 `lyfclawBackgroundPush` 字段 |
| 1a | `SettingPreferencesNotificationPage.kt` | 添加开关 UI |
| 1a | `AppEvent.kt` | 添加 `LyfclawPushReceived` 事件 |
| 1a | `ChatService.kt` | 新增 `subscribeLyfclawPushInBackground` |
| 1a | `ChatNotificationManager.kt` | 处理 `LyfclawPushReceived` 事件发通知 |
| 1b | `LyfclawPushService.kt` (新) | 前台 Service |
| 1b | `LyfclawPushManager.kt` (新) | 订阅管理器 |
| 1b | `AppModule.kt` | DI 注册 `LyfclawPushManager` (createdAtStart) |
| 1b | `RikkaHubApp.kt` | 新增通知渠道 `lyfclaw_push` |
| 1b | `AndroidManifest.xml` | 注册 `LyfclawPushService` |
| 1c | `LyfclawPushService.kt` | `onTimeout` + `AlarmManager` 重启逻辑 |

---

## 阶段二（可选）：FCM 推送

解决进程被杀后无法收到通知的问题。需要前后端双端改动。

### 1. 后端改动

#### 1.1 设备注册 API

```
POST /api/app/devices/register
  Body: { "token": "fcm_token_string", "platform": "android" }
  Auth: Bearer user_token
  → 存储到 devices 表 (user_id, token, platform, created_at)

POST /api/app/devices/unregister
  Body: { "token": "fcm_token_string" }
  → 删除对应记录
```

#### 1.2 新增 `devices` 表

```sql
CREATE TABLE devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    token TEXT NOT NULL UNIQUE,
    platform TEXT NOT NULL DEFAULT 'android',
    created_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);
CREATE INDEX idx_devices_user ON devices(user_id);
```

#### 1.3 定时任务投递时发 FCM

```python
# core/push/fcm_sender.py (新模块)
class FcmSender:
    async def send(self, token: str, title: str, body: str, data: dict):
        """通过 FCM HTTP v1 API 发送推送"""
        # POST https://fcm.googleapis.com/v1/projects/{project}/messages:send
        # 使用 service account OAuth2 token
        pass

# engine_delivery.py _async_send() 末尾追加：
if "session" in deliver_targets:
    await self._push_fcm_if_registered(principal_id, preview_text)
```

**设计原则**：
- FCM 推送是"通知"而非"消息正文"——只告知用户有新消息
- App 收到 FCM 后拉取 session DB 获取完整正文
- FCM 发送失败不影响 session delivery 结果（旁路失败不阻断）

### 2. 客户端改动

#### 2.1 集成 Firebase Messaging

```kotlin
// messaging/LyfclawFirebaseMessagingService.kt (新)
class LyfclawFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // 显示通知
        val conversationId = message.data["conversation_id"] ?: return
        val title = message.data["title"] ?: "新消息"
        val body = message.data["body"] ?: ""
        showPushNotification(UUID.fromString(conversationId), title, body)
    }

    override fun onNewToken(token: String) {
        // 上传 token 到 lyfclaw
        appScope.launch {
            lyfclawAuthClient.registerDevice(token)
        }
    }
}
```

#### 2.2 Token 生命周期管理

| 时机 | 动作 |
|---|---|
| 登录成功 | 获取 FCM token，调 `/api/app/devices/register` |
| `onNewToken` 回调 | 重新注册新 token |
| 登出 | 调 `/api/app/devices/unregister` |
| App 启动 | 检查 token 是否变化，变化则重新注册 |

### 3. 数据流（阶段二完成后）

```
进程存活时：
  → SSE 实时推送 (LyfclawPushService) → merge + 通知

进程被杀时：
  APScheduler → send_message → DB 落库
    → FCM push → 系统通知服务收到 → 弹通知
  用户点击通知 → 拉起 App → ChatVM → snapshot 同步积攒消息
```

### 4. 国内设备考量

FCM 在国内部分设备不可用（无 Google 服务）。可选方案：

| 方案 | 适用场景 |
|---|---|
| FCM | 有 Google 服务的设备（海外/部分国产） |
| 统一推送联盟 (UPS) | 支持的厂商设备 |
| 厂商推送 SDK (华为/小米/OPPO/vivo) | 各自厂商设备 |
| 轮询兜底 | 所有设备的最后保险 |

建议：阶段二先做 FCM，覆盖海外 + 有 Google 服务的国内设备。国内厂商推送可作为后续扩展，
通过抽象 `PushProvider` 接口实现多通道。

---

## 架构图

```
                    ┌─────────────────────────────────────────┐
                    │           lyfclaw Backend               │
                    │                                         │
                    │  APScheduler ──→ DirectChannel          │
                    │                     │                   │
                    │          ┌──────────┴──────────┐        │
                    │          │                     │        │
                    │    session DB           session_stream   │
                    │    (持久化)               bus           │
                    │                             │           │
                    │                    SSE endpoint         │
                    │               /api/app/sessions/        │
                    │                   {id}/stream           │
                    │                             │           │
                    │                    [阶段二] FCM API     │
                    └─────────────────────────────┼───────────┘
                                                  │
                    ┌─────────────────────────────┼───────────┐
                    │        RikkaHub Android                 │
                    │                                         │
                    │   ┌─────────────────────────────────┐   │
                    │   │     LyfclawPushService (FG)     │   │
                    │   │    ┌──────────────────────┐     │   │
                    │   │    │ LyfclawPushManager   │     │   │
                    │   │    │  subscriptions map:  │     │   │
                    │   │    │   conv1 → SSE job    │     │   │
                    │   │    │   conv2 → SSE job    │     │   │
                    │   │    │   ...                │     │   │
                    │   │    └──────────┬───────────┘     │   │
                    │   │               │                 │   │
                    │   │    ┌──────────┴───────────┐     │   │
                    │   │    │ ChatService          │     │   │
                    │   │    │ .subscribeLyfclaw   │     │   │
                    │   │    │   PushInBackground() │     │   │
                    │   │    └──────────┬───────────┘     │   │
                    │   │               │                 │   │
                    │   │    ┌──────────┴───────────┐     │   │
                    │   │    │ mergeLyfclawMessages │     │   │
                    │   │    │ (lyfclawSyncedAt     │     │   │
                    │   │    │  高水位去重)          │     │   │
                    │   │    └──────────┬───────────┘     │   │
                    │   │               │                 │   │
                    │   │    ┌──────────┴───────────┐     │   │
                    │   │    │ AppEventBus          │     │   │
                    │   │    │ .LyfclawPushReceived │     │   │
                    │   │    └──────────┬───────────┘     │   │
                    │   └───────────────┼─────────────────┘   │
                    │                   │                     │
                    │    ┌──────────────┴──────────────┐      │
                    │    │ ChatNotificationManager     │      │
                    │    │ → 系统通知 (lyfclaw_push)    │      │
                    │    └─────────────────────────────┘      │
                    │                                         │
                    │    ┌─────────────────────────────┐      │
                    │    │ ChatVM (页面打开时)          │      │
                    │    │ → subscribeLyfclawPush      │      │
                    │    │ → merge (幂等,共存安全)      │      │
                    │    └─────────────────────────────┘      │
                    │                                         │
                    │    [阶段二] FCM Messaging Service        │
                    │    → 进程被杀时收到 push → 通知           │
                    └─────────────────────────────────────────┘
```

---

## 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| Android 14+ 前台 Service 超时 | SSE 断连 | `onTimeout` + `AlarmManager` 延迟重启 |
| 多会话 SSE 连接数过多 | 电量/网络消耗 | 限制最大并发订阅数；空闲会话延迟重连 |
| 国产 ROM 后台限制 | Service 被杀 | 引导用户加电池白名单；`START_STICKY` 尝试重启 |
| 双路订阅时 merge 竞态 | 消息重复 | `lyfclawSyncedAt` 高水位去重已保证幂等 |
| 后端 `session_stream_bus` 无 replay | 断连期间消息丢失 | 不丢失——消息已落库 session DB，重连 snapshot 恢复 |
| FCM 国内不可用 | 进程被杀后无通知 | 阶段二可扩展厂商推送；阶段一不依赖 FCM |

---

## 参考文件

| 文件 | 说明 |
|---|---|
| `service/ChatService.kt:930-969` | `subscribeLyfclawPush` 现有实现 |
| `service/ChatService.kt:974-1059` | `applyLyfclawPush` + `mergeLyfclawMessages` |
| `service/ChatGenerationForegroundService.kt` | 前台 Service 参考模式 |
| `service/MemoryDreamForegroundService.kt` | 前台 Service 参考模式 |
| `service/ChatNotificationManager.kt` | 通知管理参考 |
| `service/LyfclawChatService.kt:211-232` | SSE 订阅 `subscribeSession` |
| `ui/pages/chat/ChatVM.kt:98-104` | 当前订阅启动点 |
| `data/datastore/Settings.kt:69-79` | lyfclaw 设置字段 |
| `data/model/ConversationConfig.kt:29-36` | `lyfclawSessionId` / `lyfclawSyncedAt` |
| `data/event/AppEvent.kt` | 事件总线定义 |
| `utils/NotificationUtil.kt` | 通知工具类 |
| `RikkaHubApp.kt:177-205` | 通知渠道创建 |
| `AndroidManifest.xml:112-119` | Service 注册 |
| `di/AppModule.kt:66-87` | `createdAtStart` 模式参考 |
