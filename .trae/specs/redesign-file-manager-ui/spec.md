# 文件管理页面 UI 改版 Spec

## Why
当前 FileManagerView.vue 采用终端 `ls -l` 命令行风格（深色背景、等宽字体），与项目其他页面（使用 TDesign 组件 + CSS 变量的现代 UI）视觉割裂。需要将文件管理页面改版为与项目整体 UI 一致的设计风格，使用 TDesign 组件库。

## What Changes
- 重写 `FileManagerView.vue`：终端命令行风格 → TDesign 组件标准 UI（t-table + t-list + t-button + t-input 等）
- 使用 TDesign CSS 变量（`--td-*`）替代硬编码颜色值
- 文件列表从等宽字体的纯文本行 → TDesign t-table 组件（带排序）
- 上传区域从 `$ upload <file>` 命令行样式 → TDesign t-button + t-upload 组件
- 页面布局从全屏终端 → 与项目一致的标准内容卡片布局
- router / Layout.vue / fileService.ts / types 等零改动
- **BREAKING**: 无（仅视觉改版，API 和路由不变）

## Impact
- Affected specs: `add-file-manager-page`（替换 T3-T6 文件列表/UPLOAD/下载/删除的视觉实现）
- Affected code: 仅 `frontend/src/views/FileManagerView.vue`
- 后端零改动
- 数据库零改动

## MODIFIED Requirements

### Requirement: 文件列表展示（FR-03）— 标准 UI 风格
系统 SHALL 使用 TDesign t-table 组件展示当前用户的已上传文件，表格式布局与项目其他管理页面（如 SkillManagementModal）一致。

#### Scenario: 正常加载文件列表
- **GIVEN** 用户已登录且至少上传过 1 个文件
- **WHEN** 用户进入文件管理页面
- **THEN** 显示 t-table 组件，列包含：文件名（含文件类型图标）、文件类型、大小（人类可读）、上传时间、操作（下载/删除按钮）

#### Scenario: 无文件时的空状态
- **GIVEN** 用户尚未上传任何文件
- **WHEN** 用户进入文件管理页面
- **THEN** 显示空状态提示文本 + 上传按钮引导

#### Scenario: 加载失败
- **GIVEN** API 请求失败
- **WHEN** 用户进入文件管理页面或刷新列表
- **THEN** 显示 t-alert theme="error" 错误提示 + "重试"按钮

### Requirement: 文件上传（FR-05）— 标准 UI 风格
系统 SHALL 使用 TDesign t-upload 组件提供文件上传能力。

#### Scenario: 点击上传文件
- **GIVEN** 用户在文件管理页面
- **WHEN** 用户点击"上传文件"按钮 → 选择文件 → 确认
- **THEN** 文件上传成功 → 列表自动刷新 → MessagePlugin.success 提示

#### Scenario: 上传超大文件
- **GIVEN** 用户选择超过 10 MiB 的文件
- **WHEN** 用户尝试上传
- **THEN** MessagePlugin.error 提示大小超限

#### Scenario: 上传不支持的文件类型
- **GIVEN** 用户选择不支持的文件类型
- **WHEN** 用户尝试上传
- **THEN** MessagePlugin.error 提示类型不支持

### Requirement: 文件下载 & 删除（FR-03, FR-04）— 标准 UI 风格
系统 SHALL 在 t-table 操作列提供下载和删除操作，使用 TDesign 图标按钮 + t-popconfirm 二次确认。

#### Scenario: 下载文件
- **GIVEN** 文件列表中有文件
- **WHEN** 用户点击操作列的下载按钮
- **THEN** 触发浏览器下载

#### Scenario: 确认删除文件
- **GIVEN** 文件列表中有文件
- **WHEN** 用户点击删除按钮 → t-popconfirm 弹出再点"确认"
- **THEN** 文件从列表中移除，MessagePlugin.success "已删除"

#### Scenario: 取消删除
- **GIVEN** 文件列表中有文件
- **WHEN** 用户点击删除按钮 → t-popconfirm 弹出再点"取消"
- **THEN** 文件保持不变

## Design Decisions

### 组件选型
| 区域 | 原设计（终端风格） | 新设计（标准 UI） |
|------|------------------|------------------|
| 页面容器 | `div.file-manager` 全屏终端 | 标准 `<div>` + flex column，padding 16px |
| 文件列表 | 纯文本 `<div>` 行 + 等宽字体 | `<t-table>` 组件 + TDesign 默认字体 |
| 文件上传 | `<label>` + hidden `<input>` | `<t-upload>` 组件（或 t-button + hidden input） |
| 删除确认 | `DialogPlugin.confirm` | `<t-popconfirm>` 或保持 DialogPlugin |
| 加载状态 | blinking cursor 文字 | `<t-loading>` 组件 |
| 错误状态 | 红色文字 + retry 按钮 | `<t-alert theme="error">` |
| 空状态 | `(empty directory)` 文字 | 居中文字 + 引导上传按钮 |
| 操作按钮 | 终端风 `<t-button>` | 标准 `variant="text" shape="square"` 图标按钮 |
