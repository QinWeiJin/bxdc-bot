## Context

### 当前架构

```
前端 (Vue3)
  │  POST /agent/run (SSE 流式响应)
  ▼
agent-core (NestJS, 端口 3000)
  │  接收 { instruction, context, history, enabledSkillIds, conversationId }
  │  返回 SSE 事件流
  │
  ├── LLM 调用 (OpenAI / 自定义 baseUrl)
  ├── Skill 执行 → 回调 gateway
  └── 记忆管理 (mem0)
```

前端路由只有一条 `/` → `ChatView.vue`，`ChatView` 始终渲染 `MessageList + MessageInput`。

### 关键约束

- **尽量不修改 agent-core**（AGENTS.md 7.5）
- **JDK 1.8** 编译目标
- **尽量不新增第三方包**（AGENTS.md 7.1）
- **尽量不新增环境变量**（AGENTS.md 7.2）

## Goals / Non-Goals

**Goals:**
- 左侧对话列表提供"发布为API"入口，弹出填写 API 描述的窗口
- 发布后对话页面从聊天视图切换为 API 详情视图（描述 + 地址 + 调用记录）
- API 描述在每次 API 调用时拼入 LLM 上下文
- 提供一个同步 HTTP API `POST /api/conversations/:id/agent-chat`
- 多人可并发调用同一 API，各调用方获得独立回复
- API 调用记录表追踪每次调用详情
- API 调用产生的消息在调用记录中可见

**Non-Goals:**
- 不支持 SSE 流式返回给 API 调用方（首版）
- 不支持取消发布（首版发布即不可逆）— 后续可添加
- 不修改 agent-core 代码
- 发布后不支持再切回聊天模式（对话变为只读 API 视图）

## Decisions

### 1. 发布流程

```
用户在对话中配置好 Skill
  → 侧边栏点击 "发布为API" 按钮
  → 弹出 PublishApiModal
  → 填写 "API 描述"（描述这个 API 提供什么服务）
  → 点击 "确认发布"
  → 后端: UPDATE conversations SET is_published=1, api_description='...'
  → 前端: 当前页面刷新为 ApiDetailView
```

### 2. 对话发布后的页面状态

发布后进入该对话，`ChatView.vue` 根据 `conversation.is_published` 条件渲染：

| `is_published` | 渲染内容 |
|----------------|----------|
| `false` (默认) | `MessageList` + `MessageInput`（现有聊天界面） |
| `true` (已发布) | `ApiDetailView`（API 描述 + 地址 + 调用记录表） |

**ApiDetailView 布局**:

```html
<div class="api-detail">
  <!-- API 信息卡片 -->
  <div class="api-info-card">
    <div class="api-header">
      <t-tag theme="primary">已发布</t-tag>
      <h2>{{ conversation.name }}</h2>
    </div>

    <div class="api-section">
      <label>API 描述</label>
      <p>{{ conversation.api_description }}</p>
    </div>

    <div class="api-section">
      <label>API Key</label>
      <div class="api-key-display">
        <code>{{ maskedApiKey }}</code>
        <t-button size="small" @click="copyApiKey">复制</t-button>
      </div>
      <t-button size="small" variant="outline" theme="warning" @click="regenerateApiKey">
        重新生成 Key
      </t-button>
      <p class="api-key-hint">重新生成后旧 Key 立即失效</p>
    </div>

    <div class="api-section">
      <label>调用地址</label>
      <code>POST /api/agent-chat</code>
    </div>

    <div class="api-section">
      <label>请求格式</label>
      <pre>{
  "apiKey": "c_xxxx...",
  "instruction": "你的问题",
  "callerId": "optional-caller-id"
}</pre>
    </div>

    <div class="api-section">
      <label>调用示例</label>
      <pre>curl -X POST {{ baseUrl }}/api/agent-chat \
  -H "Content-Type: application/json" \
  -d '{"apiKey": "c_xxxx...", "instruction": "你的问题"}'</pre>
    </div>
  </div>

  <!-- 调用记录表格 -->
  <div class="call-log-table">
    <h3>调用记录</h3>
    <t-table :data="callLogs" :columns="callLogColumns" />
  </div>
</div>
```

### 3. 端点设计

#### 3.1 发布端点

**`PUT /api/conversations/:id/publish`**

- **Request**: `{ "apiDescription": "该 API 提供数据分析和报告生成服务..." }`
- **认证**: `X-User-Id` header（对话所有者）
- **Response** (`200`): 更新后的 conversation 对象（含 `isPublished=true`）
- **校验**: 对话必须属于当前用户；对话不能重复发布（422）

#### 3.2 API 调用端点

**`POST /api/agent-chat`**

- **Request**:
  - `apiKey` (string, required): 对话的 API Key，格式 `c_` + 32 位 hex
  - `instruction` (string, required): 用户输入文本
  - `callerId` (string, optional): 调用方标识
- **认证**: 通过 `apiKey` 字段完成——gateway 计算 SHA-256 后查询 `conversations` 表
- **Response** (`200`):
```json
{
  "conversationId": "uuid",
  "reply": "Agent 的完整回复文本",
  "toolCalls": 3,
  "durationMs": 4521
}
```
- **校验**: apiKey 有效 + 对应对话已发布（`is_published = true`）

#### 3.3 调用记录查询端点

**`GET /api/conversations/:id/call-logs`**

- **认证**: `X-User-Id` header
- **Query**: `?page=1&size=20`
- **Response** (`200`):
```json
{
  "logs": [
    {
      "id": 1,
      "callerId": "external-app-001",
      "instruction": "帮我分析这份数据...",
      "reply": "分析结果如下...",
      "toolCallCount": 2,
      "durationMs": 3421,
      "status": "success",
      "createdAt": "2026-06-11T12:00:00"
    }
  ],
  "total": 45,
  "hasMore": true
}
```

#### 3.4 API Key 管理端点

**`GET /api/conversations/:id/api-key`**

- **认证**: `X-User-Id` header
- **Response** (`200`): `{ "apiKey": "c_xxxx..." }` — 返回明文 Key（供前端复制按钮）

**`PUT /api/conversations/:id/regenerate-api-key`**

- **认证**: `X-User-Id` header
- **Response** (`200`): `{ "apiKey": "c_xxxx_new..." }` — 返回新生成的明文 Key（仅此一次）
- **副作用**: 旧 Key 立即失效，`api_key` + `api_key_hash` 更新

### 4. API 描述的 LLM 注入机制

API 描述的核心作用是在 LLM 对话上下文中为当前 API 提供"角色定位"和"服务边界"。它不是简单的文本前缀，而是作为对话上下文的一部分，帮助 LLM 理解当前 API 的职责范围。

**注入位置**：拼接到发给 agent-core 的 `history` 数组的最前面，作为一条 `role = 'system'` 的消息：

```
gateway 组装的 history 数组:
[
  {
    role: 'system',
    content: '你是一个已发布为 API 的助手，提供以下服务：\n{api_description}\n\n请严格按照上述描述提供服务，不要偏离描述的职责范围。如果用户请求超出上述范围，请礼貌告知用户该 API 不支持此功能。'
  },
  { role: 'user', content: '历史消息1...' },
  { role: 'assistant', content: '历史回复1...' },
  ... (最近 10 条 conversation_messages)
]
```

**为什么用 history 中的 system 角色而非 instruction 前缀**：

| 方式 | 问题 |
|------|------|
| instruction 前缀 | 用户每个输入都会带这段前缀，LLM 可能误将其视为"重复指令"，弱化约束效果 |
| history 中的 system 消息 | 作为对话上下文的一部分，LLM 在整段对话中持续遵循该角色定位，约束更稳定 |

agent-core 的 `/agent/run` 接收 `history` 数组（`Array<{role, content}>`），其中 `role = 'system'` 的消息会被当作系统级上下文处理。这与 agent-core 已有的 `buildStaticSystemPrompt` 不冲突——两者叠加使用，system 消息在 history 数组中提供**对话级**约束。

**注入时机**: gateway 在组装 agent-core 请求体时，在查询到的历史消息前方插入这条 system 消息，然后一起传给 agent-core。

**效果**: LLM 在整个对话过程中会持续遵循 `api_description` 定义的服务范围，不会"忘记"自己的 API 角色。

**为什么不用 agent-core 的 system prompt**: agent-core 的 system prompt 由 `Prompts` 模块管理，修改需要改 agent-core 代码（违反 AGENTS.md 7.5）。用 `history` 数组前置 system 消息的方式在 gateway 侧即可完成，且功能等效。

### 5. API 调用执行流程

```
调用方 POST /agent-chat { instruction, apiKey }
  ↓
gateway 查询 apiKey → 校验有效性 + 获取 conversationId
  ↓
gateway 查询 conversation → 校验 is_published=true
  ↓
gateway 查询 conversation_messages 最近 10 条作为 history
  ↓
gateway 组装 history = [{role:'system', content: api_context}, ...历史消息]
  ↓
gateway 组装 instruction = "[用户输入] {instruction}"（不做前缀拼接）
  ↓
gateway POST agent-core /agent/run { instruction, history, enabledSkillIds, conversationId }
  ↓ (SSE 流消费)
聚合 agent_message → 完整 reply
统计 tool_status 事件 → toolCallCount
计算耗时 → durationMs
  ↓
gateway 写入 api_call_logs 记录
  ↓
gateway 返回 { reply, toolCalls, durationMs }
```

**并发隔离**: 每次 API 调用生成独立 `sessionId = UUID`，agent-core 为每个 sessionId 创建独立 Agent 实例，互不影响。

### 6. API Key 生成与认证方案

#### 6.1 设计原则

API Key 不采用全局共享 Token（不安全，泄露后影响所有对话），而是**每个已发布对话生成独立的 API Key**。调用方通过 API Key 调用，gateway 根据 Key 定位到对应的对话。

这样设计的好处：
- 泄露一个 Key 只影响一个对话，可单独撤销
- 用户在 API 详情页可直接查看/复制/重新生成属于该对话的 Key
- 每个 Key 天然对应当前对话，调用方不需要传 `conversationId`

#### 6.2 数据模型

`conversations` 表新增字段：

```sql
ALTER TABLE conversations
ADD COLUMN api_key VARCHAR(64) NULL
COMMENT 'API调用密钥，每个已发布对话独立生成。SHA-256哈希存储，原始值仅生成时返回一次';
```

生成规则：
- 发布对话时自动生成：`c_` + UUID（去掉连字符，32 位）
- 格式：`c_a1b2c3d4e5f6...`（`c_` 前缀表示 conversation API Key）
- 数据库中存储 `SHA-256(apiKey)` 的 16 进制摘要（不可逆），原始明文仅在生成时通过 API 响应返回一次
- 用户可在 API 详情页查看最后 4 位确认身份，完整 Key 不可二次查看

#### 6.3 发布时生成流程

```
PUT /api/conversations/:id/publish { apiDescription }
  ↓
gateway 生成 apiKey = 'c_' + UUID.replace(/-/g, '')
  ↓
gateway 存储 api_key_hash = SHA256(apiKey)
  ↓
gateway 返回: { conversation, apiKey: 'c_xxxx...' }  ← 只此一次返回原始 Key
```

#### 6.4 API 调用认证流程

```
调用方 POST /agent-chat { apiKey, instruction }
  ↓
gateway 提取 apiKey → 计算 SHA256 → 查询 conversations WHERE api_key_hash = ?
  ↓
查到了 → 校验 is_published=true → 继续处理
没查到 → 返回 401
```

调用方不需要传 `X-Api-Key` header，也**不需要在 URL 中指定 conversation_id**——API Key 本身就是对话的唯一标识。端点简化为：

```
POST /api/agent-chat
Body: { "apiKey": "c_xxxx...", "instruction": "..." }
```

#### 6.5 前端 API 详情页展示

ApiDetailView 中新增 API Key 卡片：

```
┌─────────────────────────────────────┐
│  API Key                            │
│                                     │
│  c_****...****d5e6          [复制]  │
│  （仅显示后 4 位）                   │
│                                     │
│  [重新生成 Key]                      │
└─────────────────────────────────────┘
```

- 首次发布：弹窗中显示完整 Key，提示用户"请立即保存，后续无法再次查看"
- 详情页：仅显示 `c_****...****<后4位>`，点击复制按钮复制完整 Key（需要从后端临时查询——后端存储的可逆加密或单独存储原始 Key）
- **对于 Store 方案**：如果必须支持"重新查看"和"复制"功能，则 `api_key` 字段不哈希，直接明文存储（因为对话发布者有权查看自己对话的 Key）

#### 6.6 存储方案选择

权衡"安全性"与"可用性"：

| 方案 | 安全性 | 可用性 | 选择 |
|------|--------|--------|------|
| 哈希存储（不可逆） | 高：泄露数据库不泄露 Key | 低：用户无法查看/复制 Key | ❌ |
| 明文存储 | 低：数据库泄露 = Key 泄露 | 高：用户随时查看/复制 | ⚠️ |
| **双字段存储**：`api_key` 明文 + `api_key_hash` 哈希 | 中：查询用哈希，查看用明文 | 高：认证走哈希，展示用明文 | **✅ 推荐** |

**推荐方案**：数据库中存两个字段：
- `api_key`（VARCHAR(64)）— 明文存储，供前端展示和复制
- `api_key_hash`（VARCHAR(64)）— SHA256 哈希，供 API 认证查询

API 调用时用 `api_key_hash` 做索引查询（走唯一索引），前端复制时从 `api_key` 明文读取。

#### 6.7 重新生成 Key

```
PUT /api/conversations/:id/regenerate-api-key
```

- 生成新 Key → 更新 `api_key` 和 `api_key_hash`
- 旧 Key 立即失效
- 返回新 Key（仅此一次）

#### 6.8 端点调整

由于 API Key 自带对话定位能力，端点从路径参数模式改为请求体模式：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/agent-chat` | 请求体含 `apiKey` + `instruction`，gateway 根据 Key 定位对话 |
| `GET` | `/api/conversations/:id/call-logs` | 不变，前端用（X-User-Id 认证） |
| `PUT` | `/api/conversations/:id/publish` | 不变，前端用（返回 apiKey） |
| `PUT` | `/api/conversations/:id/regenerate-api-key` | 新增，前端用 |

### 7. 数据库 Schema

#### conversations 表新增

```sql
ALTER TABLE conversations
ADD COLUMN is_published TINYINT(1) NOT NULL DEFAULT 0
COMMENT '是否已发布为API: 0=未发布, 1=已发布';

ALTER TABLE conversations
ADD COLUMN api_description TEXT NULL
COMMENT 'API描述文本，发布时填写，作为LLM对话上下文的系统消息';

ALTER TABLE conversations
ADD COLUMN api_key VARCHAR(64) NULL
COMMENT 'API调用密钥明文，供前端展示和复制';

ALTER TABLE conversations
ADD COLUMN api_key_hash VARCHAR(64) NULL
COMMENT 'API调用密钥SHA-256哈希，供认证查询';

-- 唯一索引：加速 API Key 认证查询
CREATE UNIQUE INDEX idx_api_key_hash ON conversations(api_key_hash);
```

#### conversation_messages 表新增

```sql
ALTER TABLE conversation_messages
ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'web'
COMMENT '消息来源: web=页面输入, api=API调用';
```

#### api_call_logs 新表

```sql
CREATE TABLE api_call_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(36) NOT NULL COMMENT '关联的对话ID',
  user_id VARCHAR(64) NOT NULL COMMENT '对话所有者',
  caller_id VARCHAR(128) NULL COMMENT '调用方标识（API请求中传入）',
  instruction TEXT NOT NULL COMMENT '调用方输入（原始文本，不含API上下文前缀）',
  reply LONGTEXT NOT NULL COMMENT 'Agent完整回复',
  tool_call_count INT NOT NULL DEFAULT 0 COMMENT '工具调用次数',
  duration_ms INT NOT NULL DEFAULT 0 COMMENT '响应耗时(毫秒)',
  status VARCHAR(20) NOT NULL DEFAULT 'success' COMMENT 'success | timeout | error',
  error_message TEXT NULL COMMENT '失败时的错误信息',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_conversation_id (conversation_id),
  INDEX idx_user_id (user_id),
  INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 7. 前端改动详解

#### 7.1 ConversationSidebar.vue — "发布为API"按钮

在对话列表项右侧已有一排按钮（编辑、技能配置、删除），新增一个：

```
位置: 在技能配置按钮前（或后）
图标: ShareIcon 或 ApiIcon
文案: 悬浮提示 "发布为API"
条件: 仅未发布的对话显示（is_published === false）
点击: 打开 PublishApiModal
```

已发布对话的状态标识：在对话名称旁显示一个小 "API" 标签（替代按钮），点击可跳转到对应的 API 详情页。

#### 7.2 PublishApiModal.vue — 新增组件

```
<t-dialog header="发布为 API" v-model:visible="visible">
  <t-form>
    <t-form-item label="API 描述" required>
      <t-textarea
        v-model="apiDescription"
        placeholder="描述该 API 提供的服务，例如：'该 API 接受用户输入的数据分析需求，调用平台内置的数据处理和分析技能，返回结构化的分析报告...'"
        :maxlength="2000"
        :autosize="{ minRows: 4, maxRows: 8 }"
      />
    </t-form-item>
    <t-form-item label="调用地址">
      <t-input :value="apiUrl" readonly disabled />
    </t-form-item>
    <t-alert theme="info" message="发布后对话将变为只读模式，外部系统可通过 API 地址调用" />
  </t-form>
  <template #footer>
    <t-button @click="visible = false">取消</t-button>
    <t-button theme="primary" @click="handlePublish" :loading="publishing">确认发布</t-button>
  </template>
</t-dialog>
```

#### 7.3 ApiDetailView.vue — 新增组件

发布后的对话主视图，包含：
- API 信息卡片（描述、地址、认证方式、curl 示例）
- 调用记录分页表格

调用记录表格列：

| 列名 | 字段 | 说明 |
|------|------|------|
| 调用方 | `callerId` | 调用方标识，为空则显示 "-" |
| 输入 | `instruction` | 截断显示，hover 显示全文 |
| 输出 | `reply` | 截断显示，hover 显示全文 |
| 工具调用 | `toolCallCount` | 数字 |
| 耗时 | `durationMs` | 格式化为 "3.4s" |
| 状态 | `status` | Tag: success(绿) / timeout(橙) / error(红) |
| 时间 | `createdAt` | 格式化时间 |

行点击展开：点击某行展开显示完整的输入和输出文本。

#### 7.4 ChatView.vue — 条件渲染

```html
<template>
  <Layout>
    <div class="chat-card">
      <div class="chat-main">
        <!-- 已发布 → API 详情视图 -->
        <ApiDetailView
          v-if="conversations.currentConversation?.is_published"
          :conversation="conversations.currentConversation"
        />
        <!-- 未发布 → 聊天视图（现有） -->
        <template v-else>
          <MessageList />
        </template>
      </div>
      <t-alert v-if="error" ... />
      <div class="chat-input-area" v-if="!conversations.currentConversation?.is_published">
        <MessageInput />
      </div>
    </div>
  </Layout>
</template>
```

### 8. 权限与状态校验汇总

| 操作 | 认证方式 | 校验项 |
|------|----------|--------|
| 发布对话 | `X-User-Id` | 对话所有者 + 未发布（is_published=false） |
| API 调用 | `apiKey` 字段 | apiKey 有效 + 对应对话 is_published=true |
| 查看调用记录 | `X-User-Id` | 对话所有者 |
| 重新生成 Key | `X-User-Id` | 对话所有者 + is_published=true |
| 切换对话 | `X-User-Id` | 对话所有者（已发布的也能切，看 API 详情页） |

### 9. 错误码扩展

| 场景 | HTTP 状态码 | 响应体 |
|------|-------------|--------|
| apiKey 无效或不存在 | 401 | `{ error: "Invalid API key" }` |
| 对话未发布（apiKey 对应对话未发布） | 409 | `{ error: "Conversation is not published" }` |
| 对话已发布（重复发布时） | 422 | `{ error: "Conversation is already published" }` |
| 非对话所有者 | 403 | `{ error: "Forbidden" }` |
| agent-core 不可用 | 502 | `{ error: "Agent service unavailable" }` |
| 调用超时（120s） | 504 | `{ error: "Agent processing timeout" }` |
| API 描述为空 | 400 | `{ error: "API description is required" }` |
| instruction 为空 | 400 | `{ error: "instruction is required" }` |

### 10. 配置项

| 配置 key | 默认值 | 说明 |
|----------|--------|------|
| `agent.core.url` | `http://localhost:3000` | 已有，复用 |
| `app.api.agent-timeout-seconds` | `120` | agent-core 调用超时 |

说明：不再需要 `app.api.expose-token` 全局 Token——每个对话的 API Key 独立生成并存储在数据库 `api_key` / `api_key_hash` 字段中。`SecurityConfig` 无需额外配置白名单路径。

## Risks / Trade-offs

### [风险1] 发布后不可逆
发布后对话页面变为 API 详情视图，不再显示聊天界面。用户无法继续在 Web UI 中追加消息。

**缓解**: 后续可加"取消发布"功能，切换回聊天模式。但不影响 API 的持续可用性——发布后即使"取消发布"，已有 API 调用方的调用不受影响。

### [风险2] API 描述质量影响 Agent 行为
API 描述写得不清晰时，LLM 可能出现范围偏离（回答超出描述范围的问题）。

**缓解**: 在 API 描述的拼接中加约束指令："请严格按照上述描述提供服务，不要偏离描述的职责范围"。

### [风险3] 并发调用同一 API
多个调用方同时调用时，写入 `api_call_logs` 的消息按 `created_at` 交错排列。

**缓解**: 每条调用记录独立行，`created_at` 反映真实时序。不影响各自返回内容的正确性（sessionId 隔离）。

### [风险4] API 描述长度
API 描述可能很长（2000 字），拼入 instruction 后增加 token 消耗。

**缓解**: 前端限制 maxlength=2000，且提供合理的 placeholder 引导用户简洁描述。API 描述只在每次调用时增加约 500~1000 tokens 的开销，可接受。

### [风险5] SSE 连接泄漏
调用方提前断开连接时，gateway 与 agent-core 的 SSE 连接可能残留。

**缓解**: 使用 `SimpleClientHttpRequestFactory` + broken pipe 检测；agent-core 自带 SSE Subject 断开时的清理逻辑。

### [风险6] api_call_logs 表增长
高频调用场景下该表会快速增长。

**缓解**: `idx_created_at` 索引保证按时间查询效率。后续可加归档策略（如 90 天前的记录迁移到冷存储）。

## Implementation Notes

### 文件结构

```
backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/
├── controller/
│   └── ConversationApiController.java       # 新增: publish + agent-chat + call-logs + api-key + regenerate-api-key
├── service/
│   └── ConversationApiService.java          # 新增: SSE消费 + 消息持久化 + 日志记录 + Key生成
├── entity/
│   ├── Conversation.java                    # 修改: 加 isPublished + apiDescription
│   ├── ConversationMessage.java             # 修改: 加 source
│   └── ApiCallLog.java                      # 新增: 调用记录实体
└── mapper/
    ├── ConversationMapper.java              # 修改: 加 publish 方法
    └── ApiCallLogMapper.java                # 新增: 调用记录 CRUD

frontend/src/
├── components/
│   ├── PublishApiModal.vue                  # 新增: 发布弹窗
│   ├── ApiDetailView.vue                    # 新增: API 详情视图（描述+地址+调用记录）
│   ├── MessageList.vue                      # 修改: API 来源消息标签
│   ├── ConversationSidebar.vue              # 修改: "发布为API"按钮 + 已发布标识
│   └── MessageInput.vue                     # 不修改（已发布时不渲染）
├── composables/
│   └── useConversations.ts                  # 修改: publishConversation + currentConversation
├── types/
│   └── conversation.ts                      # 修改: 加 is_published + api_description + source
├── services/
│   └── api.ts                               # 修改: publishConversation + fetchCallLogs
└── views/
    └── ChatView.vue                         # 修改: 条件渲染 ApiDetailView vs 聊天视图
```

### 核心实现路径

1. **DB Schema** — 两张表加列 + 新表 `api_call_logs`
2. **Entity + Mapper** — Conversation / ConversationMessage / ApiCallLog
3. **ConversationApiService** — publish、agent-chat（SSE 消费 + API 描述注入）、call-log 查询
4. **ConversationApiController** — 5 个端点
5. **前端类型 + API 层** — conversation.ts + api.ts + useConversations
6. **PublishApiModal** — 弹窗 + 发布请求
7. **ApiDetailView** — API 详情页面 + 调用记录表格
8. **ChatView** — `v-if="is_published"` 条件渲染
9. **ConversationSidebar** — "发布为API"按钮
