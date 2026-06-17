## ADDED Requirements

### Requirement: TRAE Architect Skill 定义与加载

系统 SHALL 在 `.trae/skills/trae-architect/SKILL.md` 中提供一个架构审视 Skill。用户在同一对话框内直接触发此 Skill 审查已生成的 OpenSpec 产物。Skill 加载时 SHALL 首先发出强指令：忽略对话历史中关于此变更的所有讨论，只基于被审工件和架构参考文件独立判断。

#### Scenario: 同一对话框内触发 + 强指令隔离
- **WHEN** 用户在当前对话框中触发 `trae-architect` Skill 审查某个 OpenSpec 变更
- **THEN** Skill 首条指令 SHALL 要求 Agent 忽略对话历史中关于此变更的需求讨论和澄清内容
- **AND** Agent SHALL 只阅读该变更的 proposal.md / design.md / specs/ / tasks.md 以及架构参考文件（AGENTS.md / technical-design.md）
- **AND** Agent SHALL 基于 SKILL.md 中的架构规则独立判断

#### Scenario: 新对话框兜底审查
- **WHEN** 用户怀疑同一对话框内的审查被对话历史污染
- **THEN** 用户 MAY 开新对话框重新触发同一 Skill
- **AND** 此时 Agent 无任何对话历史，实现完全独立审查

#### Scenario: 工件本身信息不足时不留情面
- **WHEN** 被审工件（如 design.md）对某个关键决策（如为什么修改 agent-core）没有说明
- **THEN** Agent SHALL 标记为违规
- **AND** Agent SHALL NOT 假设"对话历史里可能解释过"而放行

### Requirement: 功能清单完整性

SKILL.md SHALL 包含完整的已验证功能清单，按 10 大功能域组织（用户系统、智能对话、长期记忆、技能系统、技能市场、技能确认、异步任务、文件处理、日志审计、辅助功能），每个功能项 SHALL 标注其对应后端 Controller/Service 的源码路径。

#### Scenario: 功能清单覆盖全平台
- **WHEN** 用户或 Agent 查阅功能清单
- **THEN** 清单 SHALL 覆盖平台所有已验证的功能项
- **AND** 每个功能项 SHALL 附带源码定位引用

#### Scenario: 新功能标记
- **WHEN** 被审 spec 提出新功能但未出现在 SKILL.md 功能清单中
- **THEN** Agent SHALL 标记此功能为"未在架构知识库登记"（PASS_WITH_NOTES，不阻塞）

### Requirement: 架构设计哲学文档

SKILL.md SHALL 包含项目顶层架构设计哲学（Thin Agent Thick Tools），涵盖：三大设计原则（关注点分离、扩展点后置、最小依赖）、各层代码规约、6 条编程约束及其继承关系。

#### Scenario: 原则作为评审依据
- **WHEN** Agent 评审时发现设计违反某个架构原则
- **THEN** Agent SHALL 在评审记录中引用该原则的名称和来源段落
- **AND** 引用格式 SHALL 为 "违反原则：[原则名称]，来源：[AGENTS.md X.X / SKILL.md 第X段]"

### Requirement: 三层代码规约

SKILL.md SHALL 包含 agent-core（NestJS/TypeScript）、skill-gateway（Spring Boot/Java 1.8）、frontend（Vue 3/TypeScript）三层的目录结构规范和各层编码约束。

#### Scenario: gateway 分层违规检测
- **WHEN** 被审 design.md 或 spec 中在 gateway controller 层写业务逻辑
- **THEN** Agent SHALL 引用"controller 只做路由 + 鉴权"的规约标识此设计为违规

#### Scenario: JDK 1.8 违规检测
- **WHEN** 被审工件中提议在 gateway 侧使用 `var`、Records 或 `List.of()` 等 Java 9+ 特性
- **THEN** Agent SHALL 引用 AGENTS.md 5.4 标记为违反编程约束

### Requirement: 评审触发时机与流程

架构评审 SHALL 在 `openspec-propose` 完成全部产物生成之后、`openspec-apply` 开始实现之前，由用户在同一对话框内直接触发。评审 SHALL NOT 在 propose 过程中自动执行。

用户交互流程：
```
openspec-propose → 全部产物生成完毕（不受任何审查干扰）
       │
       ▼
同一对话框内触发 trae-architect Skill
  Skill 加载时发出强指令：忽略对话历史，只读工件 + 架构规则
       │
       ├── PASS → openspec-apply
       │
       └── NEEDS_REWORK → 评审记录含违规项 + 修改建议
               │
               ▼
           同一对话框内，用户或 Agent 按修改建议修正工件
               │
               ▼
           重新触发 trae-architect Skill（循环直到 PASS）
               │
               ▼
           openspec-apply
```

#### Scenario: propose 完成后同一对话框触发评审
- **WHEN** 用户执行 `openspec-propose` 生成全部产物
- **THEN** Agent SHALL NOT 在此过程中阻止或修改任何产物
- **AND** 用户 SHALL 在同一对话框内触发 trae-architect Skill 进行评审

#### Scenario: 评审检查 agent-core 修改
- **WHEN** trae-architect Skill 审查 proposal.md 和 design.md
- **THEN** Agent SHALL 检查变更是否涉及 agent-core 代码修改
- **AND** 若涉及，SHALL 检查工件中是否明确写了"为什么不能走 Tool 接入"
- **AND** 若未写，SHALL 标记为违规

#### Scenario: 评审检查扩展模式
- **WHEN** trae-architect Skill 审查 design.md
- **THEN** Agent SHALL 检查技术方案是否遵循"新增 Skill kind → Gateway 执行"模式
- **AND** SHALL 检查前端方案是否走 ConfigFormRenderer Schema 驱动而非在 SkillManagementModal.vue 中硬编码模板

#### Scenario: 评审检查依赖变更
- **WHEN** trae-architect Skill 审查 tasks.md 或 design.md
- **THEN** Agent SHALL 检查是否新增 npm/Maven 包
- **AND** 若新增，SHALL 检查是否附有评审理由

#### Scenario: 评审检查 spec 冲突
- **WHEN** trae-architect Skill 审查 specs/
- **THEN** Agent SHALL 对照 SKILL.md 功能清单检查新增 requirements 是否与已有 Skill 类型重叠或冲突
- **AND** SHALL 检查是否将应由 Gateway 承担的能力放入 agent-core

### Requirement: 评审结果记录格式

每次架构评审 SHALL 产生一条记录，落于 `openspec/reviews/{YYYY-MM-DD}-{change-name}.md`。

记录 SHALL 包含以下字段：
- **评审日期**：YYYY-MM-DD
- **变更名称**：对应的 OpenSpec change 名称
- **合规项**：逐条列出检查项及状态（✅ 通过 / ❌ 违规）
- **违规项**：逐条包含 → 违反的具体原则和来源引用 → **建议修改** 方案
- **结论**：PASS（全部合规）/ NEEDS_REWORK（存在违规项，附带修改建议）

#### Scenario: 全部合规
- **WHEN** 评审未发现任何违规项
- **THEN** 评审记录结论 SHALL 为 PASS
- **AND** 合规项 SHALL 逐条列出并标记 ✅

#### Scenario: 存在违规项
- **WHEN** 评审发现违反架构约束
- **THEN** 评审记录结论 SHALL 为 NEEDS_REWORK
- **AND** 每条违规项 SHALL 包含 **违反** 标签 + 引用原则 + **建议修改** 方案
- **AND** 修改方案 SHALL 给出具体可操作的修正方向，不含模糊措辞

#### Scenario: 修改后重新评审
- **WHEN** 用户或 Agent 修正工件后在同一对话框内重新触发 trae-architect Skill
- **THEN** Agent SHALL 重新读取修改后的工件并执行完整评审
- **AND** 新评审记录 SHALL 覆盖或追加到同一 change 的评审记录中
- **AND** 结论变为 PASS 后，用户方可执行 openspec-apply

### Requirement: Skill 版本同步

SKILL.md SHALL 在文件头部声明版本号和最后更新日期。当 AGENTS.md 中的编程约束发生变更时，SKILL.md SHALL 同步更新对应段落。

#### Scenario: 版本声明可追溯
- **WHEN** 用户或 Agent 查看 SKILL.md
- **THEN** 文件头部 SHALL 显示版本号和最后更新日期
- **AND** SHALL 声明"此文件需随 AGENTS.md 变更同步更新"
