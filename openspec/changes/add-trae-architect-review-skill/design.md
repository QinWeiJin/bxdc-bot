## Context

当前项目缺少一个被 AI agent 自动加载的架构知识源。项目经过 30+ 轮迭代后积累的分层哲学、扩展模式、各层代码规约、功能全貌没有被固化到任何 agent 可消费的载体中。

本变更的目标产物是一个 `.trae/skills/trae-architect/SKILL.md` 文件。用户在同一对话框中直接触发此 Skill 对已生成的 OpenSpec 产物进行架构审视，Skill 通过强指令隔离（忽略对话历史、只读工件和架构规则）来实现独立判断。

## Goals / Non-Goals

**Goals:**
- 将功能清单、架构设计哲学、各层代码规约、编程约束固化为一个 TRAE Skill 文件
- 定位为 **独立审查环节**：在 `openspec-propose` 和 `openspec-apply` 之间介入，对已生成的 proposal/design/specs/tasks 进行架构审视
- 要求 Skill 作出独立判断——只基于 SKILL.md 中的架构知识和被审工件内容，不被生成 spec 时的对话上下文影响
- 定义"不合理设计"的 5 轴判定标准和评审记录规范

**Non-Goals:**
- 不在 `openspec-propose` 过程中嵌入任何审查逻辑——propose 流程保持原样
- 不修改任何现有源代码
- 不修改 AGENTS.md 或 technical-design.md
- 不引入自动化 Gate 机制

## Decisions

### Decision 1: 同一对话框触发 + 强指令隔离

**选择：** 评审在 `openspec-propose` 完成后、`openspec-apply` 之前，用户在同一对话框内直接触发。不使用新对话框。

```
openspec-propose → 生成 proposal/design/specs/tasks（不受审查影响）
         │
         ▼
   同一对话框内触发 trae-architect Skill 进行架构评审
   Skill 首条指令：忽略对话历史，只读工件 + 架构规则独立判断
         │
         ├── PASS → openspec-apply
         └── NEEDS_REWORK → 用户/Agent 修改工件 → 同一对话框重新触发评审
                                         │
                                         └── 循环到 PASS → openspec-apply
```

**理由：** 不要求用户开新对话框，保持工作流连贯。独立性通过 Skill 内部的强指令实现（见 Decision 2）。

**兜底：** 如果用户怀疑评审质量（如担心 Agent 被对话历史影响），可以开新对话框重新触发同一 Skill 做二次审查。

### Decision 2: 强指令隔离实现独立判断

**选择：** SKILL.md 内置强指令，触发时要求 Agent 忽略对话历史中关于此变更的所有讨论，只基于以下文件做判断：

```
被审工件：
  - openspec/changes/<name>/proposal.md
  - openspec/changes/<name>/design.md
  - openspec/changes/<name>/specs/**/*.md
  - openspec/changes/<name>/tasks.md

架构参考文件：
  - AGENTS.md
  - backend/doc/technical-design.md
  - .trae/skills/trae-architect/SKILL.md（自身的架构知识）
```

**强指令文本（写入 SKILL.md 评审工作流段）：**
> "忽略本次对话中关于此变更的所有需求讨论和澄清内容。只阅读以上列出的工件文件和架构参考文件，基于 SKILL.md 中的架构规则独立判断。不要推测作者意图——如果工件本身没有说明某个关键决策（如为什么修改 agent-core），就标记为违规。"

**独立性的实际保障：**
- 虽然 Agent 在物理上无法"忘记"对话上下文，但强指令大幅降低其影响
- 要求 Agent "不留情面"——工件没写的就当不存在，不帮作者脑补
- 评审记录落盘后用户可自行验证：记录中引用的违规项是否真的只来自工件内容本身

**兜底：** 如果用户怀疑审查被对话历史污染，可以开新对话框重新触发同一 Skill，此时 Agent 确实没有任何对话历史，实现完全独立。

### Decision 3: SKILL.md 内容结构

**选择：** 采用 6 段式结构：

1. **角色声明**：TRAE-architect 的定位、触发方式、独立判断要求
2. **功能全貌**：10 大功能域 + 每项源码定位
3. **架构设计哲学**：Thin Agent Thick Tools + 三大原则 + 约束继承关系
4. **三层代码规约**：agent-core / gateway / frontend 目录结构 + 编码要求
5. **编程约束**：继承 AGENTS.md 6 条 + 5 轴判定模型
6. **评审工作流**：触发方式、检查步骤、产出物格式、结论标准

**备选：** 拆分多个 Skill 文件，不采用。TRAE 按 Skill 目录粒度加载，拆分后完整性无法保证。

### Decision 4: "不合理设计"的 5 轴判定模型

| 轴 | 判定标准 | 来源 |
|----|----------|------|
| 分层入侵 | 新功能修改了 agent-core 但未在 proposal/design 中说明"为什么不能走 Tool 接入" | AGENTS.md 5.5 |
| 扩展模式破坏 | 在前端 SkillManagementModal.vue 中为新型 Skill 硬编码模板，而非走 ConfigFormRenderer Schema 驱动 | AGENTS.md 5.5 |
| 编程约束违反 | 违反 AGENTS.md 5.1-5.4 / 5.6 中的任意一条 | AGENTS.md |
| 依赖膨胀 | 新增 npm/Maven 包但未附评审理由 | AGENTS.md 5.1 |
| 能力归属错误 | 将业务执行逻辑写在 agent-core 而非 gateway | 架构设计哲学 |

**理由：** 每条标准直接来源于 AGENTS.md 和 technical-design.md，不含主观判断。

### Decision 5: 评审结果记录与循环修改

**选择：** 每次评审落一个文件到 `openspec/reviews/{YYYY-MM-DD}-{change-name}.md`，包含：

- 评审日期、变更名称
- 合规项（逐条 + 状态）
- 违规项（**违反** 标签 + 引用原则 + **建议修改** 方案）
- 结论：PASS（全部合规）/ NEEDS_REWORK（有违规项，带修改建议）

评审发现 NEEDS_REWORK 时，由用户决定是自己改还是让 Agent 按违规项的修改建议改。修改完成后重新触发评审，直到 PASS 才进入 apply。

**理由：** 评审记录是审计链，PASS 文件证明"该变更加审并合规"。NEEDS_REWORK + 修改建议构成可操作的修正指引。

## Risks / Trade-offs

- **[风险] SKILL.md 内容过时** → 缓解：文件头部声明"需随 AGENTS.md 变更同步更新"，每次评审前先检查版本
- **[权衡] 评审增加一次 Agent 调用** → 接受。一次独立调用换架构一致性验证，值得
- **[权衡] 独立判断可能过于苛刻** → 这是设计目标。评审的标准是"工件本身是否自洽"，不依赖外部上下文
