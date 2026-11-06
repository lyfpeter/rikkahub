# Bot 列表 UI 设计

## 目标

类似微信的"好友列表 → 聊天列表"二级结构：
- 第一级：Bot 列表（类似微信好友列表）
- 第二级：该 bot 下的会话列表 + 聊天页面（现有结构不变）

## 方案

### 1. 新增 Bot 列表页面

在 `RouteActivity` 的 `Screen` 中新增 `Screen.BotList`，作为 lyfclaw 模式下的首屏。

页面内容：
- 顶部 TopAppBar（标题 "Bots"）
- LazyColumn 展示从后端 `fetchBots()` 获取的 bot 列表
- 每个 bot 卡片：displayName + label + 最后一条会话预览（可选）
- 点击 bot → 选择该 bot（更新 `lyfclawBotLabel`）→ 导航到 `Screen.Chat`

### 2. 启动导航调整

```
Before:
  App 启动 → if (isLyfclawEnabled) Screen.Chat else Screen.LyfclawLogin

After:
  App 启动 → if (isLyfclawEnabled) Screen.BotList else Screen.LyfclawLogin
  BotList → 选择 bot → Screen.Chat (新建会话)
  ChatPage drawer → 底部新增"切换 Bot"按钮 → 回到 Screen.BotList
```

### 3. Drawer 底部新增"切换 Bot"

在现有 `AssistantPicker` 旁边或替换为"Bot 选择器"：
- lyfclaw 模式下显示当前 bot 名称
- 点击 → 导航回 `Screen.BotList`

### 4. Bot 列表数据

- 复用 `LyfclawChatService.fetchBots()` 获取 bot 列表
- 新增 `BotListVM`：调用 fetchBots + 缓存到 StateFlow + 下拉刷新

### 改动文件

| 文件 | 改动 |
|---|---|
| `RouteActivity.kt` | 新增 `Screen.BotList`，启动导航改为 BotList |
| `ui/pages/bot/BotListPage.kt` (新) | Bot 列表页面 |
| `ui/pages/bot/BotListVM.kt` (新) | Bot 列表 ViewModel |
| `ui/pages/chat/ChatDrawer.kt` | 底部新增"切换 Bot"按钮 |
| `di/ViewModelModule.kt` | 注册 BotListVM |
