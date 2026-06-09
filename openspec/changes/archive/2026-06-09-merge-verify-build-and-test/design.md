## Context

合并方案（`docs/merge-plan-low-version-main.md`）的 Step 4 和 Step 5 涵盖了合并后的构建验证和测试验证。当前分支为 `temp`，已将 `lijianlong/low-version` 的增量功能通过 Step 1-3 合并进来。7 个冲突文件已手动融合。

涉及的三个子系统：
- `backend/skill-gateway` — Spring Boot 2.7 / Java 17 / Maven 3.9
- `backend/agent-core` — NestJS 11 / TypeScript 5 / Node.js
- `frontend` — Vue 3 / Vite / TypeScript

## Goals / Non-Goals

**Goals:**
- 验证 Java 编译无错（`mvn compile -q`）
- 验证 TypeScript 类型检查无错（`npx tsc --noEmit`）
- 验证前端生产构建无错（`npm run build`）
- 验证 agent-core 单元测试全部通过（`npm test`）
- 验证 skill-gateway 单元测试全部通过（`mvn test`）
- 若构建或测试失败，定位错误根源并修复

**Non-Goals:**
- 不修复合并范围之外的 pre-existing bug
- 不进行端到端集成测试或手动 QA
- 不提交代码（仅验证阶段）

## Decisions

### 验证顺序
**决策**：按 编译 → 测试 的顺序执行。先确保三个子系统编译通过，再运行测试。
- **理由**：编译错误比测试失败更基础，先修复编译问题可以避免测试在语法错误上浪费时间。
- **替代方案**：并跑编译和测试 → 不采用，因为测试可能因为编译失败而产生虚假的负面结果。

### 编译验证执行顺序
**决策**：先 Java（skill-gateway），再 TypeScript（agent-core），最后前端（Vite）。
- **理由**：SkillExecutionService 是核心服务，Java 端编译错误可能最多（新增 Entity/DTO/Controller 多，import 链复杂）。先修复 Java 再往前端推。
- **替代方案**：按耦合度排序 → 不采用，三个子系统独立编译，顺序影响不大。

### 错误修复策略
**决策**：优先修复代码问题，不修改测试用例（除非测试预期确实与合并后的正确行为不符）。
- **理由**：测试用例来自 lijianlong 侧的 58 个增量 commit，经过了验证。编译/测试失败更可能源于搬运遗漏。
- **替代方案**：先改测试再改代码 → 不采用。

## Risks / Trade-offs

- [编译错误] `SkillExecutionService` 依赖 `ApiProxyService`、`DedupConfig` 等新类，若 Step 2 遗漏则编译失败 → 从 lijianlong 分支 checkout 缺失文件
- [类型错误] `java-skills.ts` 的 `AsyncPollConfig` 新增 `pollStrategy`/`singleCallReadTimeoutSeconds` 字段，`skill-generator.ts` 的 `skillGeneratorAsyncPollSchema` 也新增了同名字段，类型需一致 → 与 lijianlong 侧比对确认字段一致
- [测试失败] `agent.ts` 新增 `streaming` 配置可能改变模型初始化行为，影响测试 mock → 更新测试 mock
- [前端构建失败] `useChat.ts` 引用了 `useThinkingMode` 和 `useFileUpload` composable，若这两个模块的 types 不匹配 → 一并从 lijianlong 侧检查完整 chain
