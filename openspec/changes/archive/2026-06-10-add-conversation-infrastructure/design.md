## Context

当前 BXDC.bot 系统没有持久化的对话会话层。前端 `useChat.ts` 在内存中维护 `messages: Ref<Message[]>`，页面关闭后消失。发送消息时从内存取最近 10 条作为 `history` 传给 agent-core，用临时生成的随机 `sessionId` 标识本次 SSE 流。这导致：无法回溯历史对话、无法切换不同项目上下文、无法按对话隔离 Skill 配置。

本 Phase（Phase 1）作为多 Session 对话系统的基础数据层，只涉及 skill-gateway 后端，不涉及前端和 agent-core。

### 约束
- **技术栈**：skill-gateway（Spring Boot 3 + MyBatis-Plus + MySQL 8）
- **编码规范**：尽量不新增第三方包；不新增环境变量；schema 变更走 JPA/Flyway 或现有 `schema-mysql.sql` 追加
- **与现有表的命名关系**：`conversation_logs` 已存在（审计日志表），新增的 `conversations` 是业务对话表，命名相似但用途不同

## Goals / Non-Goals

**Goals:**
- 创建 `conversations` 和 `conversation_messages` 两张业务表
- 提供完整的 Conversation CRUD REST API
- 支持消息落库和分页历史加载
- Spring Boot 启动时自动为存量用户创建默认对话（投产迁移）

**Non-Goals:**
- 不涉及前端对话 UI（Phase 2）
- 不涉及 agent-core Skill 过滤（Phase 3）
- 不修改现有 `conversation_logs` 审计日志表
- 不实现对话级别的软删除或归档（首版只做硬删除）

## Decisions

### Decision 1：用 JSON 列存 `enabled_skills`，而非关联表

**选择**：`conversations.enabled_skills JSON` 存储 Skill ID 数组（如 `[1, 3, 5]`）。

**理由**：
- 对话的 Skill 配置是一个小型列表（通常 3-20 个 ID），不需要额外的关联表和外键
- MySQL 8 JSON 列支持索引（虚拟列 + 函数索引），但对话数量少（< 100/user），全表扫描即可
- 避免新建关联表和 JOIN，符合"尽量不新增表"规范
- 删除 Skill 时不需级联清理（JSON 中的无效 ID 在前端查询时自然过滤）

**替代方案**：`conversation_skills` 多对多关联表 → 增加 1 张表 + 1 个 Mapper + 级联删除逻辑，过度设计。

### Decision 2：消息分页用 cursor 游标，而非 offset

**选择**：`GET /api/conversations/:id?cursor=<created_at>&limit=50`，基于 `created_at` 游标。

**理由**：
- offset 分页在大数据量下性能差（`OFFSET 1000` 需扫描前 1000 行）
- cursor 利用 `created_at` 索引，性能稳定
- 前端向上滚动加载更多是天然的 cursor 场景（"加载比这个时间更早的消息"）

**替代方案**：offset 分页 → 简单但对话消息可能数千条，offset 性能不可接受。

### Decision 3：消息落库在后端完成（非 agent-core）

**选择**：前端在 SSE 流结束后，调用 `POST /api/conversations/:id/messages` 批量落库。

**理由**：
- agent-core 是无状态推理引擎，不应承担持久化职责
- 前端拥有本轮完整的消息数组（user message + assistant 流式内容 + tool calls + tool outputs），天然适合批量提交
- 落库异步执行，失败不影响用户看到的消息内容

**替代方案**：agent-core 边流式输出边落库 → 增加 agent-core 的数据库依赖，破坏无状态架构。

### Decision 4：投产迁移用 @PostConstruct 自动执行

**选择**：`DataMigrationService` 实现 Spring `@PostConstruct`，启动时检查+迁移。

**理由**：
- 无需手动执行 SQL，部署即迁移
- 幂等性：通过 `conversations` 表已有记录判断用户是否已迁移
- 失败只是用户看不到默认对话（系统仍可正常使用），不阻塞启动

**替代方案**：独立 SQL 脚本 → 需要 DBA 手动执行，容易遗漏或在不同环境间出现偏差。

### Decision 5：API 路径 `/api/conversations`，复用 `X-User-Id` Header

**选择**：`/api/conversations` 作为顶层资源路径，通过 Header `X-User-Id` 传递用户身份。

**理由**：
- 现有 `SecurityConfig` 已对 `/api/**` 进行认证拦截，`/api/conversations/**` 自然纳入
- `X-User-Id` 是现有项目的用户身份传递方式（`TaskController`、`SkillController` 均使用）
- 路径简洁，语义清晰

## Risks / Trade-offs

| 风险 | 缓解 |
|------|------|
| `conversation_messages` 表数据量增长：活跃用户每天数百条消息，表膨胀 → | 定期清理归档（后期通过 `created_at` 分区或定时 Job）。首版不做干预。 |
| JSON 列 `enabled_skills` 中的 Skill ID 失效（Skill 被删除） → | 前端查询 Skill 详情时，调用 `GET /api/skills` 自然过滤无效 ID。 |
| 迁移在无数据库连接时启动失败 → | 迁移异常仅打印日志，不影响 skill-gateway 核心功能。 |

## Migration Plan

### 部署步骤
1. 部署新版本 skill-gateway（`schema-mysql.sql` 含新表 DDL）
2. Spring Boot 启动时 `spring.sql.init.mode=always` 自动执行新 DDL（`IF NOT EXISTS` 保证幂等）
3. `DataMigrationService.@PostConstruct` 自动检 — 为每个无对话的存量用户创建默认对话
4. 验证：调用 `GET /api/conversations`（带 `X-User-Id`）确认每个用户有至少一个对话

### 回滚
- 删除 `conversations` 和 `conversation_messages` 两张表即可
- 迁移未修改任何存量表数据，回滚无副作用

## Open Questions

- 无。所有关键决策已明确。
