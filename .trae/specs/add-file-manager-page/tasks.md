# Tasks: 文件管理页面

## T1: 导航入口 & 路由配置
**映射**: FR-01（导航入口）、FR-02（路由） | **类型**: 前端 | **优先级**: P0

### 步骤
- [x] 在 `Layout.vue` 的 header actions 区域新增「文件管理」菜单项，使用 `FolderOpenIcon`，点击跳转 `/file-manager`
- [x] 在 `src/router/index.ts` 新增 `/file-manager` 路由（lazy load `FileManagerView.vue`，需登录 `meta.requiresAuth: true`）
- [x] 验证：菜单项高亮逻辑 — 当路由匹配 `/file-manager*` 时高亮

### 变更文件
- `frontend/src/components/Layout.vue` — 新增 FolderOpenIcon import + 文件管理按钮
- `frontend/src/router/index.ts` — 新增 /file-manager 路由

### 验收标准
- 导航栏可见「文件管理」按钮
- 点击后 URL 变为 `/file-manager`
- 刷新后路由保持

---

## T2: API 服务层 & 类型定义
**映射**: 所有 FR 的数据层 | **类型**: 前端 | **优先级**: P0

### 步骤
- [x] 创建 `src/services/fileService.ts`，封装 4 个 API 调用：
  - `listFiles()` → `GET /api/files`
  - `downloadFile(id)` → `GET /api/files/download/{id}`（触发浏览器下载）
  - `deleteFile(id)` → `DELETE /api/files/{id}`
  - `uploadFile(file)` → `POST /api/files/upload`（multipart/form-data）
- [x] 根据后端 `UserFile` 实体在前端 `src/types/fileUpload.ts` 新增 `UserFileRecord` 接口（id, originalFileName, fileName, fileSize, fileType, uploadTime, parsedSummary, downloadUrl）
- [x] 复用 `apiUrl()` 工具函数（`src/services/config.ts`）构建请求 URL

### 变更文件
- `frontend/src/services/fileService.ts`（新建）
- `frontend/src/types/fileUpload.ts`（追加 `UserFileRecord` 接口）

### 验收标准
- `listFiles()` 能正确返回用户文件列表
- `downloadFile(id)` 触发浏览器下载
- `deleteFile(id)` 能成功删除文件
- `uploadFile(file)` 能成功上传文件

---

## T3: 文件管理页面 — 终端 `ls -l` 风格列表
**映射**: FR-03（文件列表展示） | **类型**: 前端 | **优先级**: P0 | **依赖**: T1, T2

### 步骤
- [x] 创建 `src/views/FileManagerView.vue`
- [x] 实现终端风格文件列表：
  - 每行格式：`-rw-r--r-- 1 {username} {size_human} {upload_time MMM dd HH:mm} {file_name}`
  - 字体：等宽字体（`Consolas`, `monospace`）
  - 背景：深色终端风格（`#1e1e1e`）
  - 文件名可点击，颜色高亮区分（`#4ec9b0` 绿色）
- [x] 实现空状态：当无文件时显示终端风格提示 `(empty directory)`
- [x] 实现加载状态：blinking cursor + Loading 文字
- [x] 实现错误状态：错误提示 + 重试按钮
- [x] 每个文件行末尾增加操作按钮：下载（DownloadIcon）、删除（DeleteIcon）
- [x] 文件大小人类可读：`formatFileSize()` B → KB → MB → GB 自动换算

### 变更文件
- `frontend/src/views/FileManagerView.vue`（新建，489 行）

### 验收标准
- 文件列表以终端风格正确渲染
- 字段对齐（权限 105px + 用户名 80px + 大小右对齐 100px + 时间 110px + 文件名弹性）
- 空文件时显示友好提示
- 加载失败时显示重试按钮

---

## T4: 文件上传功能
**映射**: FR-05（文件上传） | **类型**: 前端 | **优先级**: P1 | **依赖**: T1, T2

### 步骤
- [x] 在 FileManagerView.vue 中实现上传区域：
  - 使用 `<label>` + hidden `<input type="file">`（终端风格，非 `<t-upload>`）
  - 接受的文件类型与现有 `FILE_UPLOAD_CONFIG` 保持一致（14 种扩展名）
  - 单文件最大 10 MiB（客户端前置校验）
  - 上传成功后自动刷新文件列表
  - Toast 提示上传成功 / 失败
- [x] 上传区域放在文件列表上方，设计为终端风格 `$ upload <file>` 命令行样式

### 变更文件
- `frontend/src/views/FileManagerView.vue`（内联实现）

### 验收标准
- 点击上传区域选择文件触发上传
- 上传成功后列表自动刷新
- 超大文件提示错误
- 不支持的文件类型提示错误

---

## T5: 文件下载功能
**映射**: FR-04（下载部分） | **类型**: 前端 | **优先级**: P1 | **依赖**: T1, T2

### 步骤
- [x] 在 FileManagerView.vue 中实现文件下载：
  - 点击文件名或下载图标触发下载
  - 调用 `fileService.downloadFile(id)`（fetch blob → 创建临时的 blob URL → 触发浏览器下载）
  - 处理下载失败（Toast 错误提示）

### 变更文件
- `frontend/src/views/FileManagerView.vue`（内联实现）

### 验收标准
- 点击文件名触发浏览器下载
- 下载失败显示错误提示

---

## T6: 文件删除 & 操作确认
**映射**: FR-03（删除部分） | **类型**: 前端 | **优先级**: P1 | **依赖**: T1, T2

### 步骤
- [x] 在 FileManagerView.vue 中实现文件删除：
  - 点击删除图标 → 弹出 TDesign `DialogPlugin.confirm` 二次确认
  - 确认后调用 `fileService.deleteFile(id)`
  - 成功后从列表中移除该行，Toast 提示"已删除"
  - 失败时 Toast 提示"删除失败"，文件保留
- [x] 确认对话框内容："确定要删除文件 {fileName} 吗？此操作不可撤销。"

### 变更文件
- `frontend/src/views/FileManagerView.vue`（内联实现）

### 验收标准
- 点击删除弹出确认框
- 确认后文件从列表移除
- 取消后文件保持不变
- 删除失败显示错误提示

---

## T7: 集成测试 & 安全验证
**映射**: FR-06, FR-07（安全隔离） | **类型**: 全栈 | **优先级**: P0 | **依赖**: T1–T6

### 步骤
- [x] 前端 build 验证：`cd frontend && npm run build` 零错误（vue-tsc -b 零错误 + vite build exit 0）
- [x] 修复预存 `MessageList.vue:92` TS2322 错误（regex match group 非空断言）以通过 vue-tsc -b
- [x] 安全隔离：后端 `FileUploadController` 已通过 `X-User-Id` header 做用户隔离，前端所有请求均携带 `X-User-Id`
- [ ] 端到端流程验证（需运行服务端联调）：
  - 上传一个文件 → 列表中可见
  - 下载上传的文件 → 内容正确
  - 删除文件 → 列表移除
- [ ] 边界条件验证（需运行服务端联调）：
  - 空文件列表展示
  - 超大文件上传拦截

### 变更文件
- `frontend/src/components/MessageList.vue`（修复预存 TS 错误：1 处非空断言）

### 验收标准
- `npm run build` 零错误（已验证，exit 0）
- 上传 → 列表可见 → 下载 → 删除 全流程正常（待联调）
- 用户隔离生效（后端已实现）
- 边缘情况处理正确

---

# Task Dependencies
- **T3, T4, T5, T6** 均依赖 **T1, T2**
- **T3** 是基础页面，T4/T5/T6 在此基础上增量添加功能
- **T7** 依赖 T1–T6 全部完成
- **T1, T2** 可并行开发
- **T4, T5, T6** 可并行开发（均基于 T3 的基础框架）
