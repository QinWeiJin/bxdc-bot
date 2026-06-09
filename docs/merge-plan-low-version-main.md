# 合并方案：temp ← lijianlong/low-version（v2 · 稳妥分步版）

## 一、背景重新定位

| 项目 | 说明 |
|------|------|
| **当前分支（重构侧）** | `temp` (HEAD: `fe33256`) — "技能系统统一执行重构" |
| **被合入分支（增量功能侧）** | `lijianlong/low-version` (`e06f912`) — 58 commits 增量 |
| **共同祖先** | `02f23e1` — "pollHeaders 反序列化异常改为 warn" |
| **合并方向** | 将 lijianlong 的增量功能合入 temp 的重构架构 |

### 两边的本质差异

```
共同祖先 (02f23e1)
     │
     ├── temp 侧: 2 commits，底层重构
     │   └── dc0302e: agent ↔ gateway 交互方式重构
     │       - agent.ts: 移除 builtinToolExecutionService，改走 SkillExecutionService
     │       - java-skills.ts: -2015 行，提取 skill-shared/skill-generator/openclaw-executor
     │       - SkillController.java: -243 行，统一执行入口 /execute → SkillExecutionService
     │       - SkillManagementModal.vue: 改为 schema 驱动（ConfigFormRenderer）
     │
     └── lijianlong 侧: 58 commits，增量功能 + 部分重构
         - 思考模式、审计表（conversation_logs / tool_call_logs）
         - LLM fallback + DeepSeek 兼容
         - 异步任务通知中心（核心架构增量）
         - PERIODIC 异步收口（fire-and-forget）
         - 文件上传 + 文档解析（Word/Excel/PPT/TXT）
         - 对话下载 MD/PDF、CORS 安全修复
         - 技能系统统一执行重构（与 temp 侧部分重叠！）
         - 以及同事 PR #2~#7 的其他增量
```

**核心冲突：lijianlong 的 "技能系统统一执行重构"（PR #7, commit `dc0302e`）和 temp 侧是同一作者的同一批重构工作，但 lijianlong 侧之后又在该重构之上叠了 58 个增量 commit。temp 侧只包含重构本身（2 commit），lijianlong 侧包含"重构 + 58 个增量"。**

### 上一版合并方案的问题

上一版方案假设的是"low-version（重构版本）← main（3 commit）"——改动量小，直接 merge 可行。

本次是"temp（仅重构）← lijianlong（重构 + 58 commit 增量）"——改动量巨大，直接 merge 必然产生大量冲突，且上次合并尝试已经验证失败了。

---

## 二、冲突全景分析

### 2.1 数据总览

| 维度 | 数量 |
|------|------|
| lijianlong 改动源文件总数 | 80 |
| temp 改动源文件总数 | 34 |
| 双方都改动的文件（冲突候选） | **7** |
| 仅 lijianlong 改动（安全合入） | 73 |
| 仅 temp 改动（安全保留） | 27 |

### 2.2 七个冲突文件详情

| # | 文件 | temp 改动量 | lijianlong 改动量 | 冲突性质 | 建议策略 |
|---|------|-----------|------------------|---------|---------|
| 1 | `agent.ts` | -26/+5 | +5 | temp 删除了旧执行路径，lijianlong 加了 5 行（流式开关） | ✅ **低冲突** — 以 temp 为准，比对补入 |
| 2 | `java-skills.ts` | **-2015/+301** | -130/+150 | temp 拆分到 shared/generator/executor；lijianlong 新增了 asyncPoll SINGLE_CALL 逻辑 | ⚠️ **高冲突** — 需将 lijianlong 的 async 逻辑迁移到 temp 的新模块结构 |
| 3 | `SkillController.java` | -243/+28 | -32/+150 | temp 删除了 SSH/API 执行方法，委托给 SkillExecutionService；lijianlong 新增了异步任务 API 端点 | ⚠️ **高冲突** — temp 删掉的代码 lijianlong 可能新增了端点，需人工比对 |
| 4 | `AsyncTaskPollingScheduler.java` | +18 | +162/-19 | temp 小改；lijianlong 大改（通知中心、dedup、SINGLE_CALL） | ✅ **偏向 lijianlong** — temp 的 18 行大概率被包含 |
| 5 | `SkillManagementModal.vue` | -175/+137 | -12/+28 | temp 改为 schema 驱动渲染；lijianlong 加了异步轮询 UI | ⚠️ **中冲突** — temp 已通过 schema + ConfigFormRenderer 实现了异步轮询 UI（我们在 low-version 上已做过），需确认已有 |
| 6 | `useChat.ts` | -1/+1 | -187/+227 | temp 几乎没改；lijianlong 大改（SSE polling、通知中心、文件上传） | ✅ **偏向 lijianlong** — temp 改动可忽略 |
| 7 | `skillEditor.ts` | -4/+8 | -2/+48 | temp 小改；lijianlong 加了 asyncPoll 字段 | ✅ **偏向 lijianlong** — temp 改动可被包含 |

### 2.3 仅 lijianlong 改动的文件分类（73 个，安全合入）

#### agent-core（约 5 个修改 + 5 个新增）

| 文件 | 功能所属 |
|------|---------|
| `controller/agent.controller.ts` | SSE/思考模式/文件处理 |
| `skills/skill.manager.ts` | compat 模式、渐进披露 |
| `utils/logger.service.ts` | 审计日志增强 |
| `utils/conversation-logger.ts` | **NEW** — 对话日志落库 |
| `utils/thinking-mode.ts` / `thinking-mode.spec.ts` | **NEW** — 思考模式 |
| OpenSpec archives（多个） | **NEW** — 文档归档 |

#### skill-gateway Java（约 20+ 修改 + 15+ 新增）

| 模块 | 关键文件 |
|------|---------|
| **异步任务通知** | `AsyncTaskNotificationController.java` (NEW)、`TaskController.java`、`AsyncTaskNotificationDto.java` (NEW) |
| **文档解析** | `ExcelParserController.java` (NEW)、`WordParserController.java` (NEW)、`PptParserController.java` (NEW) |
| **对话/工具日志** | `ConversationLog.java` (NEW)、`ToolCallLog.java` (NEW)、`ConversationLogController.java` (NEW) |
| **配置** | `SecurityConfig.java`（CORS）、`DedupConfig.java` (NEW)、`SchemaMigrationRunner.java` (NEW)、`StartupRecoveryRunner.java` (NEW) |
| **实体/Mapper** | `Skill.java`、`AsyncTask.java`、`User.java` + 对应 Mapper |
| **Service** | `ApiProxyService.java`、`SkillService.java`、`UserService.java`、`AsyncPollingAuditService.java` |

#### frontend（约 15+ 修改 + 10+ 新增）

| 模块 | 关键文件 |
|------|---------|
| **文件上传** | `vendorLoader.ts` (NEW)、`fileValidator.ts` (NEW)、`fileParser.ts` (NEW)、parser 工具类 × 5 (NEW) |
| **对话下载** | `chatDownload.ts` (NEW) |
| **通知中心** | `TaskNotificationBell.vue` (NEW) |
| **思考模式** | `ThinkingMode.vue` (NEW) |
| **聊天** | `ChatView.vue`、`MessageInput.vue`（文件上传）、`MessageList.vue` |
| **配置** | `Layout.vue`、`router/index.ts`、`services/api.ts`、`vite.config.ts` |

---

## 三、分步合并方案（5 步）

### 核心原则

1. **不用 git merge**（冲突太多不可控）
2. **分 4 批搬运代码**，每批独立验证
3. **7 个冲突文件逐个手动融合**，以 temp 为基线 + 比对补入 lijianlong 增量

---

### Step 1：搬运「仅 lijianlong 新增」的文件（零冲突）

这一步直接 checkout lijianlong 侧的新文件。

```bash
git checkout temp   # 确保在 temp 分支

# 方法：从 lijianlong 侧检出所有新增（A）文件
git diff --name-status $(git merge-base temp lijianlong/low-version)..lijianlong/low-version \
  | grep "^A" | awk '{print $2}' | grep -v '/dist/' \
  | while read f; do
      git checkout lijianlong/low-version -- "$f"
    done
```

**涵盖**：所有 NEW 文件（AGENTS.md、conversation_logs 相关、文档解析器、通知中心 DTO/Controller、OpenSpec archives 等），约 50+ 文件。

**风险**：零。全是新文件，不覆盖任何 temp 已有文件。

**验证**：`git status` 确认只有新增文件在暂存区。

---

### Step 2：搬运「仅 lijianlong 修改」的文件（未冲突的修改）

```bash
# 获取仅 lijianlong 修改过的文件（排除双方都改的 7 个）
git diff --name-only $(git merge-base temp lijianlong/low-version)..lijianlong/low-version \
  | grep -v '/dist/' \
  | while read f; do
      # 跳过 7 个冲突文件
      case "$f" in
        *agent.ts|*java-skills.ts|*SkillController.java|*AsyncTaskPollingScheduler.java|*SkillManagementModal.vue|*useChat.ts|*skillEditor.ts) continue ;;
      esac
      git checkout lijianlong/low-version -- "$f"
    done
```

**涵盖**：约 73 个文件，包括前端所有新组件、文档解析器、通知中心、对话日志等。

**关键文件**（部分列举）：
- `backend/agent-core/src/controller/agent.controller.ts`
- `backend/agent-core/src/skills/skill.manager.ts`
- `backend/agent-core/src/utils/conversation-logger.ts`、`thinking-mode.ts`
- `backend/skill-gateway/.../controller/AsyncTaskNotificationController.java`、`ExcelParserController.java`、`WordParserController.java`、`PptParserController.java`
- `backend/skill-gateway/.../config/DedupConfig.java`、`SchemaMigrationRunner.java`、`StartupRecoveryRunner.java`
- `backend/skill-gateway/.../entity/ConversationLog.java`、`ToolCallLog.java`
- `backend/skill-gateway/.../service/ApiProxyService.java`、`SkillService.java`
- `frontend/src/**/*.vue`（除 SkillManagementModal）、`frontend/src/**/*.ts`（除 useChat/skillEditor）

**风险**：低。git 未报告冲突的文件，说明 temp 没动过这些文件，lijianlong 的修改可安全覆盖。

**验证**：执行 `cd backend/skill-gateway && mvn compile` 确认 Java 编译无缺类错误。

---

### Step 3：逐个手动融合 7 个冲突文件

#### 3.1 agent.ts（低冲突，以 temp 为准 + 补入 5 行）

**temp 改动**：删除了 `builtinToolExecutionService` 相关代码（26 行），改为通过 SkillExecutionService 调度。

**lijianlong 改动**（5 行）：AGENT_STREAMING 环境变量判断 + postModelHook。

**做法**：
```bash
# 保留 temp 版本
git checkout temp -- backend/agent-core/src/agent/agent.ts
# 然后手动比对 lijianlong 的 5 行：
# git diff $(git merge-base temp lijianlong/low-version)..lijianlong/low-version -- backend/agent-core/src/agent/agent.ts
# 在合适位置加入 AGENT_STREAMING 和 postModelHook 逻辑
```

**预计难度**：低（5 行改动，位置可确定）

#### 3.2 java-skills.ts（极高冲突，需重建）

**temp 改动**：-2015 行，将逻辑拆分到 3 个新文件（skill-shared.ts、skill-generator.ts、openclaw-executor.ts），原文件只保留入口逻辑。

**lijianlong 改动**：-130/+150，主要涉及：
- asyncPoll SINGLE_CALL 模式的 schema + 执行逻辑
- DeepSeek 兼容（zod schema 包装）
- skill generator 相关的 asyncPoll schema

**关键问题**：lijianlong 的改动中有一部分和 temp 的 skill-generator.ts / skill-shared.ts 是"同一功能的不同实现"——两边都做了 skill 生成重构。

**做法**：
```bash
# 1. 保留 temp 的 java-skills.ts（已经是重构后的精简版）
git checkout temp -- backend/agent-core/src/tools/java-skills.ts

# 2. 比对 lijianlong 的改动
git diff $(git merge-base temp lijianlong/low-version)..lijianlong/low-version \
  -- backend/agent-core/src/tools/java-skills.ts > /tmp/lijianlong-java-skills.patch

# 3. 逐段审查 lijianlong 的改动，将以下逻辑迁移到 temp 的新模块：
#    a. asyncPoll SINGLE_CALL 提交逻辑 → 补入 java-skills.ts 的 buildToolForSkill()
#    b. DeepSeek zod 兼容 (ensureObjectType) → 补入 skill-shared.ts
#    c. asyncPoll schema 定义 → 补入 skill-generator.ts
```

**预计难度**：最高。需要理解 temp 重构后的模块结构，逐段迁移。

#### 3.3 SkillController.java（高冲突）

**temp 改动**：-243/+28，删除了 `executeSshCommand()`、`callApi()`、`callApiAsync()` 等方法，新增 `/execute` 统一入口 + `SkillExecutionService` 委托。

**lijianlong 改动**：-32/+150，主要涉及：
- 异步任务状态查询端点（`GET /api/skills/async-tasks/{id}`、`GET /api/skills/async-tasks/my`）
- 异步任务提交端点增强

**做法**：
```bash
# 1. 以 temp 为准
git checkout temp -- backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/SkillController.java

# 2. 比对 lijianlong 新增的端点
git diff $(git merge-base temp lijianlong/low-version)..lijianlong/low-version \
  -- backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/SkillController.java > /tmp/lj-skill-controller.patch

# 3. 将 lijianlong 新增的异步任务查询端点手动加入到 temp 版本
#    注意：这些端点可能已被移到 AsyncTaskNotificationController，需确认
```

**预计难度**：中高。注意检索 lijianlong 侧新增的 Controller（如 `AsyncTaskNotificationController.java`）是否已经承载了部分端点。

#### 3.4 AsyncTaskPollingScheduler.java（偏向 lijianlong）

**temp 改动**：+18 行

**lijianlong 改动**：+162 行（异步通知中心逻辑、dedup、SINGLE_CALL）

**做法**：以 lijianlong 为准，temp 的 18 行大概率已被包含。
```bash
git checkout lijianlong/low-version -- backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/AsyncTaskPollingScheduler.java
```
之后比对 temp 的 18 行是否有独立逻辑需要保留。

#### 3.5 SkillManagementModal.vue（中冲突，偏向 temp）

**temp 改动**：-175/+137，改为 ConfigFormRenderer schema 驱动渲染。

**lijianlong 改动**：-12/+28，异步轮询 UI 元素。

**做法**：以 temp 为准（schema 驱动渲染已包含异步轮询 UI，我们在 low-version 上后续已添加 `asyncPollEnabled`/`asyncPollStrategy` 等 schema 字段）。确认 temp 版本是否正确渲染异步轮询部分，缺的补入。

```bash
git checkout temp -- frontend/src/components/SkillManagementModal.vue
# 比对 lijianlong 的 async poll UI 改动，确认 temp 已有等价实现
```

#### 3.6 useChat.ts（偏向 lijianlong）

**temp 改动**：1 行

**lijianlong 改动**：-187/+227（SSE polling 状态处理、通知中心集成、文件上传处理）

**做法**：直接用 lijianlong 版本。
```bash
git checkout lijianlong/low-version -- frontend/src/composables/useChat.ts
```

#### 3.7 skillEditor.ts（偏向 lijianlong）

**temp 改动**：-4/+8

**lijianlong 改动**：-2/+48（asyncPoll 字段、SINGLE_CALL 策略）

**做法**：直接用 lijianlong 版本，temp 的 8 行改动大概率已被包含。
```bash
git checkout lijianlong/low-version -- frontend/src/utils/skillEditor.ts
```

---

### Step 4：构建验证（每个子步骤后执行）

#### 4.1 Java 编译

```bash
cd backend/skill-gateway && mvn compile -q
```

**可能的问题**：
- 缺少新增 Entity/Mapper 导致的编译错误 → 确认 Step 2 是否遗漏文件
- SkillExecutionService 内部引用了旧路径 → 手动修复 import

#### 4.2 agent-core TypeScript 编译

```bash
cd backend/agent-core && npx tsc --noEmit
```

**可能的问题**：
- java-skills.ts 引用新的模块路径问题
- skill-shared.ts / skill-generator.ts / openclaw-executor.ts 的类型不匹配

#### 4.3 前端构建

```bash
cd frontend && npm run build
```

**可能的问题**：
- useChat.ts 和 skillEditor.ts 的导出不匹配
- SkillManagementModal.vue 的 prop 类型变化

---

### Step 5：运行测试

```bash
cd backend/agent-core && npm test
cd backend/skill-gateway && mvn test
```

---

## 四、合并后验证清单

| 检查项 | 验证方式 |
|--------|---------|
| 编译通过 | Step 4.1 / 4.2 / 4.3 |
| 测试通过 | Step 5 |
| `skill-shared.ts` 存在 | 文件存在性 |
| `skill-generator.ts` 存在 | 文件存在性 |
| `openclaw-executor.ts` 存在 | 文件存在性 |
| `ConfigFormRenderer.vue` 存在 | 文件存在性 |
| `SkillExecutionService.java` 存在 | 文件存在性 |
| `SystemSkillController.java` 存在（含新增 schema 字段） | 文件存在性 |
| 异步任务通知中心端点可用 | 启动 gateway 后 curl |
| 文档解析（Word/Excel/PPT）端点可用 | 启动 gateway 后 curl |
| 文件上传功能正常 | 前端 UI 测试 |
| 思考模式正常 | 前端 UI 测试 |
| 对话下载正常 | 前端 UI 测试 |
| asyncPoll SINGLE_CALL 策略可用 | API Skill 表单测试 |
| asyncPoll PERIODIC 策略可用 | API Skill 表单测试 |

---

## 五、风险提示

1. **java-skills.ts 是最大风险点**（temp -2015 行 vs lijianlong -130/+150）：
   - 建议先在纸上画出 temp 重构后的模块结构（skill-shared / skill-generator / openclaw-executor 三个文件的职责边界），再逐段审查 lijianlong 的 diff 决定每个改动应该放入哪个模块。
   - 如果时间紧迫，可先跳过 lijianlong 的 asyncPoll SINGLE_CALL 新逻辑（因为 low-version 上我们后续已经加了），仅确保不影响已有功能。

2. **SkillController.java 可能存在功能重叠**：
   - lijianlong 侧新增的异步任务端点可能在 `AsyncTaskNotificationController.java`（Step 1/2 已搬运）中已有等价实现。需人工确认后决定是否还要在 SkillController 中补入。

3. **schema.sql / schema-mysql.sql 不在冲突列表中**：
   - 但 lijianlong 侧新增了 `async_polling_audit_logs`、`conversation_logs`、`tool_call_logs` 三张表。Step 2 已将 lijianlong 的 `schema-mysql.sql` 搬运过来，需确认与 temp 的 schema 合并后无索引冲突。

4. **dist/ 不进 git**：合并后需在本地 `npm run build` 重新生成 agent-core 和 frontend 的 dist 产物。

5. **如果手动融合太复杂**：备选方案是放弃 temp 的底层重构，以 lijianlong/low-version 为基线，将 temp 的重构作为"新 feature"重新实施。这样只需要关注 temp 的 2 个 commit 改动，而非 58 个增量。但工作量可能更大。

---

## 六、时间预估

| 步骤 | 预计工作量 | 风险 |
|------|-----------|------|
| Step 1：搬运新文件 | 5 分钟 | 无 |
| Step 2：搬运无冲突修改 | 10 分钟 | 低 |
| Step 3.1-3.7：手动融合 7 个文件 | 2-4 小时 | 中高 |
| Step 4：构建修复 | 1-2 小时 | 中 |
| Step 5：测试修复 | 1-2 小时 | 中 |
| **合计** | **4-8 小时** | |
