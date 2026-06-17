## Why

当前项目积累了 54 个 OpenSpec 规约、30+ 个历史变更记录和一套成熟的架构设计哲学，但这些知识分散在 `technical-design.md`、`AGENTS.md`、各 spec 文件和源码中。新人（人类或 AI agent）接手项目时缺少一个统一的"架构真相源"，导致方案设计容易违反既定的分层原则、扩展模式和编程约束。需要一个TRAE Skill 将这些梳理结果固化为可被 AI agent 自动加载的上下文，在后续变更中承担架构审视角色。

## What Changes

- 新增一个 TRAE Skill `TRAE-architect`（落于 `.trae/skills/trae-architect/SKILL.md`），作为项目的架构知识库和审查入口
- Skill 包含：平台功能清单（66 项已验证功能）、分层架构设计哲学（Thin Agent Thick Tools）、各层代码规约（agent-core / gateway / frontend）、6 条编程约束及其执行标准
- Skill 赋予架构师角色权威：对后续 OpenSpec proposal / design / spec 进行架构一致性审查，明确指出不合理设计，记录评审结果

## Capabilities

### New Capabilities
- `trae-architect-review`: TRAE 架构审视 Skill，包含功能清单、架构设计哲学、各层代码规约、编程约束，提供 spec 审查和方案评审能力，记录评审历史

### Modified Capabilities
<!-- 无 -->

## Impact

- 新增文件：`.trae/skills/trae-architect/SKILL.md`
- 影响范围：后续所有 OpenSpec proposal / design / spec 的评审流程
- 不涉及任何代码修改，纯知识库构建
