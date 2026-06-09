## Context

temp 分支包含 agent ↔ gateway 交互方式的底层重构（2 commits），lijianlong/low-version 在此基础上叠加了 58 个增量 commit。双方 80 个源文件中 7 个存在冲突，其余 73 个可安全搬运。

参考：`docs/merge-plan-low-version-main.md`

## Goals / Non-Goals

**Goals:**
- 将 lijianlong 侧所有无冲突文件（新增 + 修改）完整搬运到 temp 分支
- 搬运后 temp 分支可编译（或至少不引入新编译错误）

**Non-Goals:**
- 不处理 7 个冲突文件（留待下一步）
- 不做功能验证
- 不运行测试

## Decisions

### 不用 git merge，用 git checkout <remote> -- <file>

`git merge` 会产生大量冲突标记，逐个解决成本高。改为从 lijianlong 分支逐个 `git checkout` 无冲突文件，精确控制。

### Step 1 只搬新增（A）文件

`git diff --name-status ... | grep "^A"` 筛选全部新增文件，跳过 dist/ 目录。这些文件在 temp 侧不存在，零冲突。

### Step 2 搬修改（M）文件，排除 7 个冲突文件

`git diff --name-only ...` 获取全部修改文件，过滤掉 `agent.ts|java-skills.ts|SkillController.java|AsyncTaskPollingScheduler.java|SkillManagementModal.vue|useChat.ts|skillEditor.ts` 这 7 个。

### 不搬 dist/ 文件

按 AGENTS.md 规则，dist/ 不进 git，由本地 build 生成。

## Risks / Trade-offs

- [lijianlong 侧有 schema-mysql.sql 修改，temp 侧也有] → Step 2 会覆盖为 lijianlong 版本，包含新增的 `async_polling_audit_logs`/`conversation_logs`/`tool_call_logs` 表定义，可能与 temp 的 `async_tasks` 索引有差异 → 后续 Step 3 再处理
- [搬运后 agent-core 引用了尚未融合的 java-skills.ts 旧代码] → agent.controller.ts / skill.manager.ts 等文件可能 import 了 temp 侧已删除的导出 → 编译可能报错，但这是预期的（留待 Step 3 修复）
- [搬运后 frontend 可能 TS 报错] → SkillManagementModal.vue 和 useChat.ts/skillEditor.ts 不同步 → 预期留待 Step 3
