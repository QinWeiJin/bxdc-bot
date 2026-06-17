## Context

当前 SkillHub 搜索逻辑位于 `SkillHub.vue` 的 `filteredExtendedSkills` computed 属性中：

```typescript
// 当前（仅名称搜索）
if (searchQuery.value.trim()) {
  const q = searchQuery.value.trim().toLowerCase()
  result = result.filter((s) => s.name.toLowerCase().includes(q))
}
```

`Skill` 接口包含 `name`、`description`、`createdBy` 三个可搜索文本字段。`createdBy` 存储创建者用户 ID，但在卡片展示中会映射为用户名/作者展示。

Skill 总量通常在 50 以内，全量加载后前端过滤即可，不增加 API 复杂度。

## Goals / Non-Goals

**Goals:**
- 搜索框同时匹配 Skill 的 `name`、`description`、`createdBy` 三个字段
- 不区分大小写，任一字段包含关键词即匹配
- 复用现有搜索输入框，用户无需改变操作习惯
- 搜索与现有筛选（激活状态、可见性）保持联动

**Non-Goals:**
- 不增加后端搜索 API
- 不新增前端搜索模式切换 UI
- 不引入模糊搜索库

## Decisions

### D1：搜索逻辑扩展方案

**选择：在 `filteredExtendedSkills` 内直接扩展 `filter` 条件为多字段 OR 匹配**

```typescript
if (searchQuery.value.trim()) {
  const q = searchQuery.value.trim().toLowerCase()
  result = result.filter((s) =>
    (s.name || '').toLowerCase().includes(q) ||
    (s.description || '').toLowerCase().includes(q) ||
    (s.createdBy || '').toLowerCase().includes(q)
  )
}
```

**备选方案及排除理由：**
- **后端新增 `?keyword=` 参数做数据库 LIKE 搜索**：Skill 总量小，前端过滤已足够；新增 API 参数违反"尽量不修改 agent-core"和"尽量不新增环境变量"的约束
- **引入 fuse.js 等模糊搜索库**：违反"尽量不新增第三方包"的约束，且当前简单子串匹配已能满足需求

### D2：`createdBy` 字段可搜索性

`createdBy` 存储的是用户 ID（如 `"890728"`），但 Skill 卡片展示的是作者标识。用户搜索时可能输入 ID 字符串来定位。该字段为可选（`?: string`），需做空值防护。

## Risks / Trade-offs

[Risk] `createdBy` 是用户 ID 而非显示名，用户可能不知道要搜的 ID → 当前 Skill 卡片已展示作者标识，用户可见什么就能搜什么；后续如需按显示名搜索可扩展用户查询映射。

[Risk] `description` 可能包含较长文本，子串匹配可能产生预期外匹配 → 这是子串搜索的固有特性，用户可按需缩小关键词。

## Open Questions

- 是否需要同时为 `SkillManagementModal.vue`（二级管理页）扩展搜索字段？（管理页已有搜索框 CSS 骨架但未实现搜索逻辑；如果当前已实现则一同扩展）
