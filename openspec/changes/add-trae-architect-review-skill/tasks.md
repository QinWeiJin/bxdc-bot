## 1. 目录准备

- [x] 1.1 创建 `.trae/skills/trae-architect/` 目录
- [x] 1.2 创建 `openspec/reviews/` 目录

## 2. SKILL.md 核心内容编写

- [x] 2.1 编写角色声明段落：架构师身份、同一对话框触发方式、强指令隔离要求（首条指令忽略对话历史）
- [x] 2.2 编写功能清单段落：按 10 大功能域列出所有已验证功能项 + 源码定位引用
- [x] 2.3 编写架构设计哲学段落：Thin Agent Thick Tools、三大设计原则、约束继承关系
- [x] 2.4 编写三层代码规约段落：agent-core / gateway / frontend 各自的目录结构 + 编码要求
- [x] 2.5 编写编程约束段落：继承 AGENTS.md 6 条约束 + 5 轴"不合理设计"判定模型
- [x] 2.6 编写评审工作流段落：触发方式（propose 后同一对话框直接触发）、强指令文本、检查步骤、评审记录模板、循环修改流程、新对话框兜底方案

## 3. 评审记录模板

- [x] 3.1 创建 `openspec/reviews/REVIEW_TEMPLATE.md`，包含：评审日期、变更名称、5 轴合规项 checklist、违规项（引用原则 + 修改建议）、PASS / NEEDS_REWORK 结论格式
- [x] 3.2 模板包含 PASS 和 NEEDS_REWORK 两种结论的示例记录

## 4. 验证

- [x] 4.1 对本次变更自身的 proposal/design/specs/tasks 执行一次独立评审（模拟用户分派 trae-architect Skill）
- [x] 4.2 将自审结果记录为 `openspec/reviews/2026-06-17-add-trae-architect-review-skill.md`
- [x] 4.3 验证 SKILL.md 中每个功能项均可在源码中找到对应文件
- [x] 4.4 验证 SKILL.md 中的约束条款与 AGENTS.md 无矛盾、无遗漏
