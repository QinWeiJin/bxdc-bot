## 1. 数据库 Schema 变更

- [x] 1.1 `conversations` 表新增 `is_published TINYINT(1) NOT NULL DEFAULT 0`
- [x] 1.2 `conversations` 表新增 `api_description TEXT NULL`
- [x] 1.3 `conversations` 表新增 `api_key VARCHAR(64) NULL`（明文存储，供前端展示复制）
- [x] 1.4 `conversations` 表新增 `api_key_hash VARCHAR(64) NULL`（SHA-256 哈希，供认证查询）+ 唯一索引
- [x] 1.5 `conversation_messages` 表新增 `source VARCHAR(10) NOT NULL DEFAULT 'web'`
- [x] 1.6 创建 `api_call_logs` 表
- [x] 1.7 更新 `schema-mysql.sql` 建表语句
- [x] 1.8 `Conversation.java` — 新增 `isPublished`、`apiDescription`、`apiKey`、`apiKeyHash` 字段
- [x] 1.9 `ConversationMessage.java` — 新增 `source` 字段
- [x] 1.10 新增 `ApiCallLog.java` Entity
- [x] 1.11 新增 `ApiCallLogMapper.java`

## 2. Gateway 后端 — 发布端点

- [x] 2.1 `ConversationApiController.java` — 实现 `PUT /api/conversations/{id}/publish`
- [x] 2.2 `ConversationApiService.publish()` — 校验 + 生成 `apiKey`（`c_` + UUID）+ SHA-256 哈希 → 更新 DB + 返回 `apiKey`（仅此一次）
- [x] 2.3 `ConversationMapper` 加 `selectByApiKeyHash` 方法

## 3. Gateway 后端 — API 调用端点

- [x] 3.1 `ConversationApiController.java` — 实现 `POST /api/agent-chat`（通过 `apiKey` 字段认证）
- [x] 3.2 `ConversationApiService.agentChat()` — SHA256(apiKey) → 查 conversation → 校验 is_published → 将 apiDescription 作为 system 消息注入 history 数组 → 调用 agent-core → SSE 消费 → 写 api_call_logs
- [x] 3.3 实现 SSE 流逐行消费 + `confirmation_request` 自动拒绝
- [x] 3.4 编写 `SHA256` 工具方法（JDK 自带 `MessageDigest`，不新增依赖）

## 4. Gateway 后端 — 调用记录 + Key 管理

- [x] 4.1 `ConversationApiController.java` — 实现 `GET /api/conversations/{id}/call-logs`
- [x] 4.2 `ConversationApiService.getCallLogs()` — 分页查询
- [x] 4.3 `ConversationApiController.java` — 实现 `PUT /api/conversations/{id}/regenerate-api-key`
- [x] 4.4 `ConversationApiService.regenerateApiKey()` — 生成新 Key → 更新 `api_key` + `api_key_hash` → 返回新 Key
- [x] 4.5 `ConversationApiController.java` — 实现 `GET /api/conversations/{id}/api-key` 返回明文 Key（供前端复制按钮）

## 5. 前端 — 类型与 API 层

- [x] 5.1 `types/conversation.ts` — `Conversation` 加 `is_published`、`api_description`、`api_key`；`ConversationMessage` 加 `source`
- [x] 5.2 `types/conversation.ts` — 新增 `ApiCallLog` 接口
- [x] 5.3 `services/api.ts` — 新增 `publishConversation()`、`fetchCallLogs()`、`regenerateApiKey()`、`fetchApiKey()` 函数
- [x] 5.4 `useConversations.ts` — 新增 `currentConversation` computed、`publishConversation()` 方法

## 6. 前端 — 发布弹窗

- [x] 6.1 新增 `PublishApiModal.vue` — API 描述 textarea + 发布后展示 API Key 明文（一次性）+ "请立即保存"提示
- [x] 6.2 确认发布 → 调用 `publishConversation()` → 弹窗展示 apiKey → 关闭后刷新为 ApiDetailView

## 7. 前端 — 侧边栏发布入口

- [x] 7.1 `ConversationSidebar.vue` — 未发布对话项右侧新增"发布为API"按钮
- [x] 7.2 已发布对话项名称旁显示 "API" 标签
- [x] 7.3 点击按钮打开 `PublishApiModal`

## 8. 前端 — API 详情视图

- [x] 8.1 新增 `ApiDetailView.vue` — API 信息卡片：API Key（掩码+复制+重新生成）、API 描述、调用地址 `POST /api/agent-chat`、请求格式 JSON、curl 示例
- [x] 8.2 API Key 掩码展示：`c_****...****<后4位>` + 复制按钮 → 调用 `GET /api/conversations/:id/api-key` 获取明文
- [x] 8.3 "重新生成 Key"按钮 → 调用 `PUT /api/conversations/:id/regenerate-api-key` → 展示新 Key（一次性）
- [x] 8.4 API 调用记录分页表格 + 行展开

## 9. 前端 — ChatView 条件渲染

- [x] 9.1 `ChatView.vue` — 根据 `conversations.currentConversation?.is_published` 条件渲染 `ApiDetailView` 或现有聊天视图
- [x] 9.2 已发布时隐藏 `MessageInput`，未发布时正常显示
- [x] 9.3 `useConversations` 暴露 `currentConversation` computed（从 `conversations` 数组和 `currentConversationId` 计算）

## 10. 配置

- [x] 10.1 `application.properties.example` 添加 `app.api.agent-timeout-seconds` 配置项注释
- [x] 10.2 不新增全局 Token 配置（API Key 按对话独立生成，存储在数据库）

## 11. 数据迁移

- [x] 11.1 编写 DDL 脚本（ALTER TABLE conversations × 4 + CREATE TABLE api_call_logs + ALTER TABLE conversation_messages × 1 + CREATE UNIQUE INDEX）
- [x] 11.2 已有数据默认值覆盖（`is_published=0`, `source='web'`, `api_key=NULL`, `api_key_hash=NULL`）

## 12. 验证

- [ ] 12.1 点击"发布为API" → 弹窗 → 填写描述 → 确认 → 展示 API Key → 页面变为 API 详情视图
- [ ] 12.2 API 详情页显示掩码 Key，可复制，可重新生成
- [ ] 12.3 已发布对话侧边栏显示 "API" 标签
- [ ] 12.4 `curl POST /api/agent-chat -d '{"apiKey":"c_xxx","instruction":"hello"}'` → 返回 reply → 调用记录+1
- [ ] 12.5 错误 apiKey → 401
- [ ] 12.6 正确 apiKey 但对话未发布 → 409
- [ ] 12.7 两个调用方同 Key 同时调用 → 各自独立回复 → 两条记录
- [ ] 12.8 超时 120s → api_call_logs status='timeout' → 504
- [ ] 12.9 agent-core 不可用 → status='error' → 502
- [ ] 12.10 调用记录分页正常
