## ADDED Requirements

### Requirement: 搜索框（增强：名称 + 介绍 + 作者）

SkillHub 一级页面 Extended Skills Tab 的搜索框 SHALL 同时按 Skill **名称**（`name`）、**介绍**（`description`）和 **作者**（`createdBy`）进行过滤。

#### Scenario: 输入关键词匹配名称

- **WHEN** 用户在搜索框中输入 Skill 名称中包含的关键词
- **THEN** Extended Skills 列表 SHALL 显示名称包含该关键词的 Skill
- **AND** 不区分大小写

#### Scenario: 输入关键词匹配介绍

- **WHEN** 用户在搜索框中输入 Skill 介绍（`description`）中包含的关键词
- **THEN** Extended Skills 列表 SHALL 显示介绍包含该关键词的 Skill
- **AND** 不区分大小写

#### Scenario: 输入关键词匹配作者

- **WHEN** 用户在搜索框中输入 Skill 作者标识（`createdBy`）中包含的关键词
- **THEN** Extended Skills 列表 SHALL 显示作者包含该关键词的 Skill
- **AND** 不区分大小写

#### Scenario: 关键词同时命中多字段

- **WHEN** 用户在搜索框中输入的关键词同时出现在某个 Skill 的名称和介绍中
- **THEN** 该 Skill SHALL 在列表中仅出现一次（不重复展示）

#### Scenario: 清空搜索关键词

- **WHEN** 用户清空搜索框
- **THEN** Extended Skills 列表 SHALL 恢复显示全部 Skill（受当前筛选条件约束）

#### Scenario: 搜索与筛选联动

- **WHEN** 用户同时设置搜索关键词和筛选条件
- **THEN** 列表 SHALL 同时应用搜索（name + description + createdBy）和筛选（激活状态 + 可见性），展示交集结果

---

### Requirement: 搜索框提示文案

SkillHub 一级页面 Extended Skills Tab 的搜索框 placeholder SHALL 明确提示用户可搜索的内容范围。

#### Scenario: 搜索框显示提示文案

- **WHEN** 用户查看 Extended Skills Tab 的搜索框
- **THEN** 搜索框 SHALL 显示 placeholder 文案，提示支持按"名称、介绍、作者"搜索
