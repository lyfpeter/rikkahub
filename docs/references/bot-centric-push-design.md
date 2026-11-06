# Bot 中心化推送设计文档

## 背景

当前推送模型是 per-conversation 的：每个会话维护独立的 SSE 连接，连接到 scoped session key
`bot:<bot_id>:conv:<conv_id>`。问题：

1. 一个用户有 N 个会话就需要 N 条 SSE 连接，资源浪费
2. `lyfclawSessionId` 需要首次发消息后才能获得（chicken-and-egg 问题）
3. 新会话在发第一条消息前无法收到定时任务推送

## 目标

改为类似微信的 **per-bot 订阅模型**：
- 一个 bot 一条 SSE 连接，而非一个会话一条
- 后端推送事件携带 `conversation_id`，客户端根据它分发到对应会话
- 新会话不需要先发消息就能收到推送
- 大幅减少 SSE 连接数（通常 1-3 个 bot vs 20 个会话）

## 方案：多路复用 SSE 连接

### 后端改动

#### 1. 新增多路复用 SSE 端点

`GET /api/app/sessions/stream?sids=bot:x:conv:1&sids=bot:x:conv:2`

或更简洁：`GET /api/app/bots/{bot_label}/stream` — 订阅该 bot 的所有会话推送。

复用现有 `session_stream_bus`，但事件 payload 中携带 `session_id` / `conversation_id` 供客户端分发。

#### 2. session_stream_bus 增强

在 `publish(sid, event)` 时，event 不再是 `None`（"重新计算快照"），而是携带结构化信息：

```python
{
    "type": "push",
    "session_id": "bot:x:conv:1",
    "conversation_id": "abc123",
}
```

这样多路复用订阅端收到事件后可以知道是哪个会话有更新。

#### 3. 端点实现

```python
@app.get("/api/app/bots/{bot_label}/stream")
async def bot_stream(bot_label, principal_id = Depends(api.require_user)):
    # 1. 解析 bot_label → bot_id
    # 2. 校验该 principal 是否拥有此 bot
    # 3. 订阅 bot 级别的 bus key: f"bot:{bot_id}"
    # 4. send_message 时除了 publish 到 conv 级 key，也 publish 到 bot 级 key
```

### 客户端改动

#### 1. LyfclawPushManager 改为 per-bot 订阅

```
Before:
  LyfclawPushManager
    → 扫描 DB 中所有有 lyfclawSessionId 的会话
    → 每个会话一条 SSE 连接（最多 20 条）

After:
  LyfclawPushManager
    → 从 Settings 读取当前选中的 bot_label（或从后端获取 bot 列表）
    → 每个 bot 一条 SSE 连接（通常 1-3 条）
    → 收到事件后按 conversation_id 分发到对应本地会话
```

#### 2. 事件分发逻辑

```kotlin
// 收到 bot 级推送事件
fun onBotPushEvent(botLabel: String, event: BotPushEvent) {
    val conversationId = event.conversationId
    // 查找本地是否有对应会话
    val convId = findConversationByLyfclawId(conversationId)
    if (convId != null) {
        // 已有会话：merge 消息
        chatService.mergeLyfclawMessages(convId, event.candidates)
    } else {
        // 未知会话：可能是新会话的定时任务，发通知
        showNotification(event.preview)
    }
}
```

#### 3. 不再依赖 lyfclawSessionId

当前模型下 `lyfclawSessionId` 是 per-conversation 的 scoped key。改为 per-bot 后：
- SSE 连接只需要 bot_label（从 Settings 直接读取）
- 不需要等 `Meta` 事件落库 `lyfclawSessionId` 后才能订阅
- 新会话也能立即收到推送

#### 4. 会话与 bot 的映射

当前 `Conversation.title` 被用作 lyfclaw 的 `conversation_id`（ChatService.kt:788）。
需要确保：
- 每个 conversation 都能反查到它属于哪个 bot
- 收到推送时能按 `conversation_id` 找到本地会话

方案：在 `ConversationConfig` 中新增 `lyfclawConversationId: String?` 字段，
首次发消息时从 `Meta` 事件获取并落库。推送分发时用这个字段反查本地会话。

## 数据流

```
定时任务触发
  → send_message(chatid, body, bot_id)
    → store.append_message(sid, "assistant", text)
    → session_stream_bus.publish(sid, {conversation_id: ..., text: ...})
    → session_stream_bus.publish(f"bot:{bot_id}", {conversation_id: ..., text: ...})
      ↓
    bot 级 SSE 连接收到事件
      ↓
    LyfclawPushManager.onBotPushEvent(botLabel, event)
      → 按 conversation_id 查找本地会话
      → 找到 → mergeLyfclawMessages + 通知
      → 未找到 → 弹通知（用户点击后打开/新建会话）
```

## 改动范围

| 组件 | 改动 |
|---|---|
| 后端 `session_stream_bus.py` | publish 时携带 conversation_id；新增 bot 级 publish |
| 后端 `direct.py` send_message | 除 conv 级 publish 外，额外 publish 到 bot 级 |
| 后端 `sessions.py` | 新增 `/api/app/bots/{bot_label}/stream` 端点 |
| 客户端 `LyfclawPushManager` | 从 per-conv 改为 per-bot 订阅 |
| 客户端 `LyfclawChatService` | 新增 `subscribeBot(botLabel)` 方法 |
| 客户端 `ConversationConfig` | 新增 `lyfclawConversationId` 字段 |
| 客户端 `ChatService` | 推送事件按 conversation_id 分发 |

## 优势

- SSE 连接数从 N（会话数）降到 M（bot 数，通常 1-3）
- 新会话无需先发消息即可收推送
- `lyfclawSessionId` 不再是必需的引导条件
- 更接近微信的"一个好友一条连接"模型
