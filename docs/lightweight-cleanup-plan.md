# RikkaHub 轻量化 — 残留引用清理计划

> 日期：2026-10-31
> 前置 commit：`3487c403` refactor: 轻量化裁剪 — 删除 7 个非必要模块（-71,726 行）
> 状态：✅ 已完成 — 由 `23ed7251` refactor: 轻量化残留引用清理 — 恢复构建并移除已删模块引用 收尾
> （已核对：7 个模块已删除，`app/src/main` 中残留引用已清空，编译恢复）

---

## 现状

7 个模块（terminal/workspace/search/mediagen/oauth/web/web-ui）和 71 个关联文件已删除，import 行已批量清理，Gradle 配置已更新。但 20 个保留文件中仍有**代码体引用**（字段、参数、方法调用、UI 组件等）指向已删除的类，导致编译失败。

## 执行策略

在每个文件修改后跑 `./gradlew :app:compileGithubDebugKotlin -x processGithubDebugGoogleServices -x generateGithubDebugLocaleConfig -x processGithubDebugResources` 验证，根据错误信息定位下一个修复点。

---

## 文件清单与修改内容

### 第 1 批：DI 模块（编译关键路径）

| # | 文件 | 修改内容 |
|---|------|---------|
| 1 | `di/DataSourceModule.kt` | 删除 `McpManager`、`ProviderAccountManager`、`MediaGenerationManager`、`workspaceDao()`、`mediaCreationDao()`、`genMediaDao()` 的 `single { }` 绑定；从 `ProviderManager` 构造函数删除 `accountCredentials` 参数；删除 `SearchService.init()` 调用 |
| 2 | `di/RepositoryModule.kt` | 删除 `WorkspaceManager`、`RootfsInstaller`、`SshConnectionPool`、`WorkspaceRepository`、`MediaCreationRepository`、`GenMediaRepository` 的 `single { }` 绑定 |
| 3 | `di/AppModule.kt` | 删除 `WebServerManager`、`WorkspaceTerminalSessionManager` 的 `single { }` 绑定；从 `ChatToolFactory` 构造函数删除 `workspaceRepository` 参数；从 `ChatRequestFactory` 构造函数删除 `workspaceRepository` 参数；删除 `MediaCreationForegroundService` 引用 |
| 4 | `di/ViewModelModule.kt` | 删除 `WorkspaceVM`、`WorkspaceDetailVM`、`WorkspaceSshEditorVM`、`ImgGenVM`、`MediaCreationVM`、`MediaCreationSessionsVM`、`SearchVM` 的 `viewModelOf { }` 注册；从 `AssistantDetailVM` 构造函数删除 `workspaceRepository` 参数 |

### 第 2 批：数据层

| # | 文件 | 修改内容 |
|---|------|---------|
| 5 | `data/datastore/Settings.kt` | 为 `searchServices`、`searchCommonOptions`、`searchServiceSelected`、`mediaGenerationProviders`、`imageGenerationModelId`、`mcpServers`、`webServerEnabled`、`webServerPort`、`webServerJwtEnabled`、`webServerAccessPassword`、`webServerLocalhostOnly` 字段添加 `@Transient` 注解；删除对应 import |
| 6 | `data/datastore/migration/PreferenceStoreV1Migration.kt` | 删除对 search/media/workspace 字段的迁移引用 |
| 7 | `data/db/AppDatabase.kt` | 从 `entities` 列表删除 `WorkspaceEntity::class`、`MediaCreation*Entity::class`；删除 `workspaceDao()`、`mediaCreationDao()`、`genMediaDao()` 抽象函数；version +1 并添加空 migration |
| 8 | `data/sync/BackupManager.kt` | 删除 `MediaCreationFiles` 引用和相关过滤逻辑 |

### 第 3 批：服务层

| # | 文件 | 修改内容 |
|---|------|---------|
| 9 | `service/ChatService.kt` | 删除 `mcpManager` 构造参数；删除 `InvalidMcpServerNamesException` 引用；删除 `shouldUseExternalWebSearch` 引用；简化 `warnIfToolsUnavailable` 方法（移除 MCP/search 检查）；保留 `lyfclawChatService` 参数和 `runLyfclawTurn` 逻辑 |
| 10 | `data/ai/ChatRequestFactory.kt` | 删除 `workspaceRepository` 构造参数；删除 `WorkspaceReminderTransformer` 引用；如果有 search 相关转换器引用也删除 |
| 11 | `data/ai/tools/ChatToolFactory.kt` | **如果文件已被删除则跳过**；否则删除 `SearchService`、`McpManager`、`workspaceRepository` 等引用。如果整个文件只服务于本地 AgentLoop，确认删除 |

### 第 4 批：UI 路由与页面

| # | 文件 | 修改内容 |
|---|------|---------|
| 12 | `RouteActivity.kt` | 删除以下 Screen 定义和 `entry<Screen.XXX>` 渲染：`Workspaces`、`WorkspaceDetail`、`WorkspaceTerminal`、`WorkspaceSshEditor`、`WorkspaceFileEditor`、`ImageGen`、`MediaCreationSessions`、`MediaCreation`、`SettingSearch`、`SettingSearchDetail`、`SettingMedia`、`SettingMediaProviderDetail`、`SettingWeb`、`MessageSearch`；删除 `mediaCreationRepository` 字段；删除 `ACTION_MEDIA_CREATION` 处理；删除相关 import；**保留** `LyfclawLogin`/`LyfclawRegister`/`LyfclawAccount` 路由 |
| 13 | `RikkaHubApp.kt` | 删除 `cleanupWorkspaceTempDirs()` 和 `checkWorkspaceIntegrity()` 方法及调用；删除 `startWebServerIfEnabled()` 方法及调用；删除 `resumeMediaCreations()` 方法及调用；删除 `WebServerService` 引用；删除 Web 服务器通知渠道创建 |
| 14 | `ui/pages/chat/ChatDrawer.kt` | 删除 media creation 和 imggen 菜单项 |
| 15 | `ui/pages/chat/ChatPage.kt` | 删除 `WorkspaceCompletionProvider` 引用和 `workspaceRepository` 注入 |
| 16 | `ui/pages/setting/SettingVM.kt` | 删除 `McpManager` 字段和引用 |
| 17 | `ui/pages/setting/SettingPage.kt` | 删除 search service、media、web server、MCP 设置条目 |
| 18 | `ui/pages/setting/components/ProviderConfigure.kt` | 删除 `ProviderAccountManager` 引用和 OAuth 登录 UI |

### 第 5 批：辅助文件

| # | 文件 | 修改内容 |
|---|------|---------|
| 19 | `ui/components/ai/FilesPicker.kt` | 删除 `WorkspaceRepository`、`WorkspaceEntity` 引用；删除 workspace 选择器 UI（`WorkspacePickerListItem`、CWD 选择器） |
| 20 | `ui/pages/assistant/detail/AssistantDetailVM.kt` | 删除 `workspaceRepository` 构造参数 |
| 21 | `ui/pages/assistant/detail/AssistantBasicPage.kt` | 删除 workspace 相关 UI 引用 |
| 22 | `ui/components/message/ChatMessageEditedFiles.kt` | 检查并删除 workspace 引用（如有） |
| 23 | `ui/components/richtext/Markdown.kt` | 检查并删除 search/workspace 引用（如有） |
| 24 | `service/LyfclawChatService.kt` | 检查是否有对已删类的引用（LyfclawSseEvent 中的 `MediaCreation` 等误匹配） |

### 第 6 批：AndroidManifest.xml

| # | 文件 | 修改内容 |
|---|------|---------|
| 25 | `app/src/main/AndroidManifest.xml` | 删除 `WorkspaceDocumentsProvider` 注册；删除 `WebServerService` 注册；删除 `MediaCreationForegroundService` 注册 |

---

## 验证步骤

每个批次完成后执行：

```bash
cd /work/agent/rikkahub
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:compileGithubDebugKotlin --no-daemon \
  -x processGithubDebugGoogleServices \
  -x generateGithubDebugLocaleConfig \
  -x processGithubDebugResources \
  -x mergeGithubDebugResources \
  -x packageGithubDebugResources
```

根据编译错误 `e:` 行定位下一个需要修复的文件。

全部完成后执行完整编译（需要 x86_64 环境或 Android Studio）：

```bash
./gradlew assembleGithubDebug
```

---

## 注意事项

1. **保留 lyfclaw 代码**：`LyfclawChatService.kt`、`LyfclawAuthClient.kt`、`AuthInterceptor.kt`、`ui/pages/auth/*`、`Settings.kt` 中的 lyfclaw 字段、`ChatService.kt` 中的 `runLyfclawTurn`
2. **保留 speech 和 document**：这两个模块的引用不动
3. **保留 ai 模块的类型定义**：`UIMessage`、`UIMessagePart`、`MessageRole`、`ProviderSetting` 等
4. **Settings 字段用 @Transient**：不删除字段，避免 DataStore 反序列化崩溃
5. **DB version +1**：删除 entity 后需要 Room schema migration
6. **旧版兼容**：`runTurn()` 本地 AgentLoop 路径如果因删除 ChatToolFactory/ChatRequestFactory 导致无法编译，可以将其简化为 `return TurnResult.Failed` 或直接删除（lyfclaw 模式下不走此路径）

---

## 预估工作量

| 批次 | 文件数 | 预估复杂度 |
|------|--------|-----------|
| 第 1 批 DI 模块 | 4 | 中（每个文件 10-20 处修改） |
| 第 2 批 数据层 | 4 | 中（Settings + AppDatabase 较复杂） |
| 第 3 批 服务层 | 3 | 中（ChatService 有多处引用） |
| 第 4 批 UI 路由 | 7 | 大（RouteActivity 改动最多） |
| 第 5 批 辅助文件 | 6 | 小（每文件少量修改） |
| 第 6 批 Manifest | 1 | 小 |
| **合计** | **25** | 中（需编译反馈逐步修复） |
