# 用户自助创建 Bot 方案

## 背景

当前 bot 由平台在 `bots_config.json` 中预配置，用户只能从列表中选择已有 bot。
用户希望能自己创建 bot，自定义**名称、显示名、角色设定（system prompt）**。
Provider/model 由平台决定，用户无权修改。

## 约束

- 用户不能选择 provider/model（平台权限）
- 用户可设置：label（唯一标识）、displayName（显示名）、systemPrompt（角色设定）
- 创建后可修改 displayName 和 systemPrompt
- 创建后的 bot 归属创建者

## 后端方案

### 1. 新增 Bot 模板机制

平台在 `bots_config.json` 中配置一个**默认模板 bot**（如 `__default_template__`），
包含 provider、model、channel 等基础设施配置。用户创建 bot 时从模板继承这些字段。

```json
{
  "bot_id": "__default_template__",
  "label": "__default_template__",
  "provider": "openai",
  "model": "gpt-4o",
  "direct": { "host": "0.0.0.0", "port": 8080 }
}
```

### 2. 新增用户面 API

```
POST /api/app/bots
  Body: { "label": "my-bot", "display_name": "我的助手", "system_prompt": "你是一个翻译助手" }
  Auth: Bearer user_token (require_user)
  → 从默认模板继承 provider/model/channel
  → 调用 create_local_bot 写入 identity.db
  → 将 system_prompt 写入 bot 的 definition_json
  → 设置归属为创建者
  → 返回创建的 bot 信息

PUT /api/app/bots/{bot_label}
  Body: { "display_name": "新名称", "system_prompt": "新角色" }
  → 更新 bot 的 display_name 和 system_prompt

DELETE /api/app/bots/{bot_label}
  → 删除用户创建的 bot（只能删自己创建的）
```

### 3. System Prompt 存储

当前 system prompt 来自 bot 工作目录的 `SOUL.md` 文件。
用户创建的 bot 的 system_prompt 存入 `definition_json`，
修改 prompt builder 在 `load_soul_md` 之前检查 `definition.system_prompt`：

```python
def load_soul_md(bot):
    # 优先使用 DB definition 中的 system_prompt
    definition = get_bot_definition(bot.bot_id)
    if definition and definition.get("system_prompt"):
        return definition["system_prompt"]
    # 回退到 SOUL.md 文件
    ...
```

### 4. 新建路由文件

```
app/admin/routes/userapi/bot_create.py
```

注册到 `userapi/__init__.py` 的 `register_all` 中。

## 客户端方案

### 1. BotListPage 新增"创建 Bot"入口

- 右上角 FAB 或 TopAppBar action 按钮："+"
- 点击弹出创建表单（BottomSheet 或新页面）

### 2. 创建表单

```
┌─────────────────────────────┐
│  New Bot                    │
│                             │
│  Label (唯一标识)            │
│  ┌─────────────────────────┐│
│  │ my-translator           ││
│  └─────────────────────────┘│
│                             │
│  Display Name (显示名)       │
│  ┌─────────────────────────┐│
│  │ 翻译助手                 ││
│  └─────────────────────────┘│
│                             │
│  System Prompt (角色设定)    │
│  ┌─────────────────────────┐│
│  │ 你是一个专业的中英翻译助手││
│  │ ...                      ││
│  └─────────────────────────┘│
│                             │
│         [Cancel]  [Create]  │
└─────────────────────────────┘
```

- Label：英文+数字+连字符，创建后不可修改
- Display Name：任意文字，可修改
- System Prompt：多行文本，可修改

### 3. 编辑已有 Bot

BotListPage 每个 bot 项的长按菜单或编辑图标：
- 重命名（已有）
- 编辑角色设定（新增）
- 删除（仅限自己创建的）

### 4. 客户端 API

```kotlin
// LyfclawChatService.kt
suspend fun createBot(label: String, displayName: String, systemPrompt: String): LyfclawBot
suspend fun updateBot(botLabel: String, displayName: String?, systemPrompt: String?)
suspend fun deleteBot(botLabel: String)
```

### 5. 客户端新增文件

```
ui/pages/bot/BotCreateSheet.kt   — 创建/编辑表单 BottomSheet
```

## 数据流

```
用户填写表单 → POST /api/app/bots
  → 后端从默认模板继承 provider/model
  → create_local_bot(label, definition={provider, model, system_prompt, ...})
  → 设置 owner = 创建者
  → 返回 bot 信息
  → 客户端刷新 bot 列表
  → 用户点击新 bot → 进入聊天
  → 后端组装 system prompt 时优先读 definition.system_prompt
```

## 改动范围

| 组件 | 改动 |
|---|---|
| 后端 `bot_create.py` (新) | POST/PUT/DELETE 端点 |
| 后端 `__init__.py` | 注册新路由 |
| 后端 `vllm_native_prompt.py` | load_soul_md 优先读 definition.system_prompt |
| 后端 `user_bots_merge.py` | 合并时透传 system_prompt 字段 |
| 客户端 `LyfclawChatService.kt` | 新增 createBot/updateBot/deleteBot |
| 客户端 `BotListVM.kt` | 新增 createBot/updateBot/deleteBot |
| 客户端 `BotListPage.kt` | 顶部"+"按钮 + 长按菜单 |
| 客户端 `BotCreateSheet.kt` (新) | 创建/编辑表单 |

## 权限控制

- 创建 bot：任何已登录用户可用 label 创建（label 唯一性校验）
- 编辑/删除 bot：只有 bot 的 owner 可以
- Provider/model：始终从模板继承，用户传入的 provider/model 被忽略
