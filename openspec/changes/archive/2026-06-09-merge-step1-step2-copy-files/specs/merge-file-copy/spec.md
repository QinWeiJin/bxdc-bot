## ADDED Requirements

### Requirement: 从 lijianlong 分支安全搬运无冲突文件
系统 SHALL 从 lijianlong/low-version 分支将无冲突文件搬运到 temp 分支，分为新增文件和仅一方修改的文件两类。

#### Scenario: 搬运新增文件（Step 1）
- **WHEN** 执行 `git diff --name-status $(merge-base)..lijianlong/low-version | grep "^A" | grep -v "/dist/"`
- **THEN** 对所有匹配文件执行 `git checkout lijianlong/low-version -- <file>`
- **AND** temp 分支中存在这些新增文件

#### Scenario: 搬运仅一方修改的文件（Step 2）
- **WHEN** 执行 `git diff --name-only $(merge-base)..lijianlong/low-version | grep -v "/dist/"`
- **AND** 排除 7 个冲突文件（agent.ts、java-skills.ts、SkillController.java、AsyncTaskPollingScheduler.java、SkillManagementModal.vue、useChat.ts、skillEditor.ts）
- **THEN** 对剩余文件执行 `git checkout lijianlong/low-version -- <file>`
- **AND** temp 分支中这些文件的内容与 lijianlong/low-version 一致

#### Scenario: 不搬运 dist 产物
- **WHEN** 匹配到 `/dist/` 路径的文件
- **THEN** 系统 SHALL 跳过这些文件
- **AND** 不将其加入 temp 分支
