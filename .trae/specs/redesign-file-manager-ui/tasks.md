# Tasks: 文件管理页面 UI 改版

## T1: 重写 FileManagerView.vue — 标准 UI 布局 + 文件列表
**类型**: 前端 | **优先级**: P0 | **依赖**: 无（纯视觉改版）

### 步骤
- [x] 重写 `<template>` 为 TDesign 标准布局：
  - 顶部操作栏：`<t-button theme="primary"><UploadIcon /> 上传文件</t-button>` + 隐藏 `<input type="file">`
  - 文件列表：`<t-table>` 组件，列定义：文件名（含图标）、类型、大小、时间、操作
  - 每列使用 `<template #colKey="{ row }">` 插槽自定义渲染
- [x] 文件名列：左侧显示文件类型图标（`getFileIconComponent()` 根据扩展名返回 `FileWordIcon` / `FileExcelIcon` / `FilePowerpointIcon` / `FilePdfIcon` / `FileImageIcon` / `FileIcon`），文件名文本可点击下载
- [x] 文件大小列：使用 `formatFileSize()` 转换为人类可读格式
- [x] 上传时间列：使用 `formatTime()` 格式化为 `YYYY-MM-DD HH:mm`
- [x] 操作列：`<t-space>` 内放置下载 `<t-button>` + 删除 `<t-popconfirm>` 包裹 `<t-button>`
- [x] 实现加载/错误/空状态三态切换（t-loading / t-alert / 空状态提示 + 上传引导按钮）
- [x] 页面顶部显示标题 "文件管理" + 文件数量统计

### 变更文件
- `frontend/src/views/FileManagerView.vue`（完全重写）

### 验收标准
- t-table 正确渲染文件列表，列对齐
- 文件类型图标根据扩展名正确区分
- 加载/错误/空状态正确显示
- 操作列有下载和删除按钮
- 页面标题 "文件管理" 显示

---

## T2: 文件上传功能（标准 UI 组件）
**类型**: 前端 | **优先级**: P0 | **依赖**: T1

### 步骤
- [x] 实现 hidden `<input type="file">` 点击触发文件选择
- [x] 前端大小/类型校验（≥10 MiB 拦截，14 种扩展名校验）
- [x] 上传成功后自动刷新 t-table 数据
- [x] MessagePlugin.success/error 通知

### 变更文件
- `frontend/src/views/FileManagerView.vue`

### 验收标准
- 点击"上传文件"按钮触发文件选择
- 上传成功列表自动刷新
- 大小超限/类型不支持显示错误提示

---

## T3: 文件下载 & 删除（标准 UI 交互）
**类型**: 前端 | **优先级**: P0 | **依赖**: T1

### 步骤
- [x] 下载：下载按钮点击 → `fileService.downloadFile(id)` → 浏览器下载
- [x] 删除：t-popconfirm 二次确认 → `fileService.deleteFile(id)` → 列表移除 + MessagePlugin.success
- [x] 下载/删除失败 → MessagePlugin.error

### 变更文件
- `frontend/src/views/FileManagerView.vue`

### 验收标准
- 下载按钮触发浏览器下载
- 删除按钮弹出 t-popconfirm
- 确认删除后文件从列表移除
- 失败时显示错误 Toast

---

## T4: 构建验证
**类型**: 前端 | **优先级**: P0 | **依赖**: T1, T2, T3

### 步骤
- [x] `cd frontend && npx vue-tsc -b` 零错误
- [x] `cd frontend && npm run build` 零错误（exit 0）
- [x] 确认无新增 npm 包、无新增 env 变量、无新增文件（仅修改 FileManagerView.vue）

### 变更文件
- 无（纯验证）

### 验收标准
- vue-tsc -b 零错误
- npm run build exit 0

---

# Task Dependencies
- **T2, T3** 依赖 **T1**（在同一文件内增量）
- **T4** 依赖 T1–T3
- T1/T2/T3 作为同一文件的连续编辑实现
