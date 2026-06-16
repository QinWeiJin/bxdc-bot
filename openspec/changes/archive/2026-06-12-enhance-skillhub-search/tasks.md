## 1. 前端 — SkillHub 一级页面搜索增强

- [x] 1.1 `SkillHub.vue` `filteredExtendedSkills` computed：扩展搜索过滤条件，从仅 `name` 匹配改为 `name || description || createdBy` 三字段 OR 匹配
- [x] 1.2 确保 `description` 和 `createdBy` 字段存在空值防护（`?? ''`）
- [x] 1.3 `SkillHub.vue` 搜索框 `placeholder` 从"搜索 Skill 名称..."改为"搜索名称、介绍、作者..."

## 2. 验证

- [x] 2.1 按名称搜索：输入 Skill 名称片段，列表正确过滤
- [x] 2.2 按介绍搜索：输入 Skill 描述中的关键词，列表正确过滤
- [x] 2.3 按作者搜索：输入创建者 ID 片段，列表正确过滤
- [x] 2.4 搜索与筛选联动：同时设置搜索关键词 + 激活状态/可见性筛选，结果取交集
- [x] 2.5 清空搜索框：列表恢复全量展示
- [x] 2.6 空值 Skill（无 description/createdBy）搜索不报错
