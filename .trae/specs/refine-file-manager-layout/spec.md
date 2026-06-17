# 文件管理页面上传按钮下移 + 分页 Spec

## Why
当前上传按钮位于页面顶部右侧，用户上传后需滚动回顶部；当文件超过 10 个时列表无分页，体验不佳。需将上传按钮移至底部居中，并为超过 10 条的数据添加分页。

## What Changes
- 上传按钮从 `page-header` 移至页面底部居中
- 当 `files.length > 10` 时启用 `t-table` 内置分页
- 空状态的上传引导按钮保留在空状态区域内
- 仅修改 `frontend/src/views/FileManagerView.vue`
- **BREAKING**: 无

## Impact
- Affected specs: `redesign-file-manager-ui`（布局微调）
- Affected code: `frontend/src/views/FileManagerView.vue`
- 后端零改动

## ADDED Requirements

### Requirement: 上传按钮底部居中
系统 SHALL 将"上传文件"按钮放置在页面最底部，水平居中显示。

#### Scenario: 按钮位置
- **GIVEN** 用户在文件管理页面
- **WHEN** 页面渲染完成
- **THEN** 底部居中显示"上传文件"按钮（无论是否有文件）

#### Scenario: 空状态保留上传引导
- **GIVEN** 用户无文件
- **WHEN** 页面显示空状态
- **THEN** 空状态区域内保留上传引导按钮，与底部上传按钮同时存在

### Requirement: 文件列表分页
系统 SHALL 在文件超过 10 个时启用分页，每页显示 10 条。

#### Scenario: 文件 ≤ 10 个
- **GIVEN** 文件列表 ≤ 10 条
- **WHEN** 页面渲染
- **THEN** 不显示分页控件

#### Scenario: 文件 > 10 个
- **GIVEN** 文件列表 > 10 条
- **WHEN** 页面渲染
- **THEN** t-table 显示分页控件，每页 10 条，可翻页
