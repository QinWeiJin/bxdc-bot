# 架构评审记录模板

> **使用方式**: 复制此模板，替换占位符 `{...}`，删除本提示行。

---

# 架构评审记录

- **评审日期**: {YYYY-MM-DD}
- **变更名称**: {change-name}
- **评审依据**: `.trae/skills/trae-architect/SKILL.md` v{version}

## 合规项

| # | 检查项 | 结果 |
|---|--------|------|
| 1 | proposal: 是否涉及 agent-core 代码修改 → 若涉及，是否写了理由 | {✅ / ❌} |
| 2 | proposal: 是否新增 npm/Maven 包 → 若新增，是否附评审理由 | {✅ / ❌} |
| 3 | proposal: 变更是否与现有 Skill 类型重叠或冲突 | {✅ / ❌} |
| 4 | design: 技术方案是否遵循 "Skill kind → Gateway 执行" 模式 | {✅ / ❌} |
| 5 | design: 前端是否走 ConfigFormRenderer Schema 驱动 | {✅ / ❌} |
| 6 | design: 是否违反 JDK 1.8 约束 | {✅ / ❌} |
| 7 | design: 是否违反零 TS6133 / 不新增环境变量等约束 | {✅ / ❌} |
| 8 | design: 是否有跨层调用或职责错位 | {✅ / ❌} |
| 9 | specs: 新增 requirements 是否与现有功能重叠或冲突 | {✅ / ❌} |
| 10 | specs: 是否将 Gateway 能力放入 agent-core | {✅ / ❌} |
| 11 | specs: requirements 格式是否合规 | {✅ / ❌} |
| 12 | tasks: 实现路径是否匹配各层代码规约 | {✅ / ❌} |
| 13 | tasks: 是否有遗漏的迁移步骤 | {✅ / ❌} |
| 14 | tasks: 是否新增了不必要的文件 | {✅ / ❌} |

## 违规项

<!-- 如有违规，逐条列出。无违规则删除此段 -->

### ❌ 违规: {违规标题}

- **违反**: {原则名称}，来源: {AGENTS.md X.X / SKILL.md 第X段}
- **工件位置**: {文件路径 + 段落引用}
- **建议修改**: {具体可操作的修正方向}

## 结论

**{PASS / NEEDS_REWORK}**

<!--
  PASS = 所有检查项通过 → 可执行 openspec-apply
  NEEDS_REWORK = 存在违规项 → 修正工件后重新触发评审 → 循环到 PASS
-->

---

## 示例 1: PASS

# 架构评审记录

- **评审日期**: 2026-06-17
- **变更名称**: add-login-rate-limit
- **评审依据**: `.trae/skills/trae-architect/SKILL.md` v1.0.0

## 合规项

| # | 检查项 | 结果 |
|---|--------|------|
| 1 | proposal: 是否涉及 agent-core 代码修改 | ✅ 不涉及 |
| 2 | proposal: 是否新增 npm/Maven 包 | ✅ 不新增 |
| 3 | proposal: 变更是否与现有 Skill 类型重叠 | ✅ 不重叠 |
| 4 | design: 技术方案是否遵循 "Skill kind → Gateway 执行" | ✅ 纯 gateway 配置变更 |
| 5 | design: 前端是否走 ConfigFormRenderer Schema 驱动 | ✅ 不涉及前端 |
| 6 | design: 是否违反 JDK 1.8 约束 | ✅ 不违反 |
| 7 | design: 是否违反零 TS6133 / 不新增环境变量 | ✅ 不违反 |
| 8 | design: 是否有跨层调用或职责错位 | ✅ 无 |
| 9 | specs: 新增 requirements 是否与现有功能重叠 | ✅ 无冲突 |
| 10 | specs: 是否将 Gateway 能力放入 agent-core | ✅ 不涉及 agent-core |
| 11 | specs: requirements 格式是否合规 | ✅ 合规 |
| 12 | tasks: 实现路径是否匹配各层代码规约 | ✅ 匹配 |
| 13 | tasks: 是否有遗漏的迁移步骤 | ✅ 无遗漏 |
| 14 | tasks: 是否新增了不必要的文件 | ✅ 无多余文件 |

## 结论

**PASS**

---

## 示例 2: NEEDS_REWORK

# 架构评审记录

- **评审日期**: 2026-06-17
- **变更名称**: add-custom-ssh-executor
- **评审依据**: `.trae/skills/trae-architect/SKILL.md` v1.0.0

## 合规项

| # | 检查项 | 结果 |
|---|--------|------|
| 1 | proposal: 是否涉及 agent-core 代码修改 → 若涉及，是否写了理由 | ❌ 涉及 agent-core 但未写理由 |
| 2 | proposal: 是否新增 npm/Maven 包 | ✅ 不新增 |
| 3 | proposal: 变更是否与现有 Skill 类型重叠 | ❌ 与现有 kind:"ssh" 重叠 |
| 4-14 | ... | ... |

## 违规项

### ❌ 违规: 新增独立 SSH 执行路径与现有 kind:"ssh" 重叠

- **违反**: 能力归属错误，来源: SKILL.md 架构设计哲学 原则2 "扩展点后置"
- **工件位置**: proposal.md "What Changes" 段落, design.md "Decisions" 段落
- **建议修改**: 删除新增的 SSH 执行路径，改为在现有 SSH Extension Skill (kind: "ssh") 框架下扩展。如需新增 SSH 相关能力，通过在 SystemSkillController.buildSshConfigSchema() 中扩展配置 schema 实现，而非在 agent-core 新增独立 Tool。

### ❌ 违规: 修改 agent-core 代码但未说明原因

- **违反**: 分层入侵，来源: AGENTS.md 5.5
- **工件位置**: design.md 未涉及此决策说明
- **建议修改**: 在 design.md 中新增 "为什么不能走 Tool 接入" 的说明段落。如确实无法走 Tool 接入，需列出技术瓶颈、对 agent-core 的影响范围、对现有 Skill 类型的兼容性影响。否则必须改为 Gateway Tool 接入方式。

## 结论

**NEEDS_REWORK**
