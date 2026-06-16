## Why

当前 SkillHub 搜索框仅支持按 Skill **名称**（`name`）搜索。用户无法通过 Skill 介绍（`description`）或作者（`createdBy`）来定位 Skill。随着平台 Skill 数量增长，仅靠名称搜索已不够用——用户可能记得某个 Skill 的功能描述却想不起确切名称，或想快速找到某位同事创建的所有 Skill。

## What Changes

- 扩展现有搜索框的匹配范围：在已有名称搜索基础上，同时搜索 Skill **介绍**（`description`）和 **作者**（`createdBy`），使用**同一个输入框**，用户无需切换搜索模式
- 搜索行为：不区分大小写，任一字段（name / description / createdBy）包含关键词即匹配
- 搜索框 `placeholder` 文案同步更新，提示用户可搜索"名称、介绍、作者"
- 不影响已有的激活状态筛选和可见性筛选，搜索与筛选继续联动

## Capabilities

### New Capabilities
<!-- No new capability — this is purely a modification of existing search behavior -->

### Modified Capabilities
- `skill-hub-ui`: 搜索框的匹配范围从仅名称扩展到名称 + 介绍 + 作者

## Impact

- **前端 `SkillHub.vue`**：修改 `filteredExtendedSkills` computed 中的搜索逻辑（约第 43 行）
- **前端 `SkillManagementModal.vue`**（如有二级管理页面搜索）：同样扩展匹配字段
- 不涉及后端 API 变更，不新增参数，纯前端过滤逻辑改动
