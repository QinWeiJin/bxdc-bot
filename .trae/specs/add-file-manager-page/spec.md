# 文件管理页面 Spec

## Why
当前 BXDC.bot 缺少集中的文件管理中心，用户无法直观查看、管理已上传的全部文件。需要在顶端导航栏新增「文件管理」入口，提供独立的文件管理页面，以类 FTP `ls -l` 终端风格展示用户文件，支持浏览、下载、删除和上传。

## What Changes
- 前端 Layout.vue 导航栏新增「文件管理」菜单项（FolderOpenIcon）
- 前端新增 `/file-manager` 路由（独立一级页面，需登录）
- 前端新增 FileManagerView.vue 页面（终端 `ls -l` 风格文件列表）
- 前端新增 `src/services/fileService.ts` API 层（封装 `/api/files` 的 list/download/delete/upload）
- 后端零改动 — 复用现有 `FileUploadController` 4 个端点
- 数据库零改动 — 复用 `user_files` 表

## Impact
- Affected specs: 无（新增独立功能模块，不修改现有 spec）
- Affected code:
  - `frontend/src/components/Layout.vue` — 新增 1 个菜单项
  - `frontend/src/router/index.ts` — 新增 1 条路由
  - `frontend/src/views/FileManagerView.vue` — 新建文件管理页面
  - `frontend/src/services/fileService.ts` — 新建 API 服务层
- **BREAKING**: 无

## ADDED Requirements

### Requirement: 导航入口 & 路由（FR-01, FR-02）
系统 SHALL 在顶端导航栏功能区提供「文件管理」菜单项，点击后页面跳转到 `/file-manager` 独立一级页面。

#### Scenario: 用户点击导航栏文件管理菜单
- **GIVEN** 用户已登录且在任意页面
- **WHEN** 用户点击导航栏「文件管理」菜单项
- **THEN** 页面跳转到 `/file-manager`，菜单项高亮

#### Scenario: 直接访问文件管理 URL
- **GIVEN** 用户已登录
- **WHEN** 用户直接访问 `/file-manager`
- **THEN** 显示文件管理页面，导航栏「文件管理」菜单项高亮

#### Scenario: 未登录访问文件管理
- **GIVEN** 用户未登录
- **WHEN** 用户访问 `/file-manager`
- **THEN** 重定向到 `/login` 页面

### Requirement: 文件列表展示（FR-03）— 终端 `ls -l` 风格
系统 SHALL 以类 FTP `ls -l` 命令输出格式展示当前用户的全部已上传文件，字段对齐 Linux FTP 客户端输出风格。

#### Scenario: 正常加载文件列表
- **GIVEN** 用户已登录且至少上传过 1 个文件
- **WHEN** 用户进入文件管理页面
- **THEN** 显示终端风格文件列表，每行包含：权限标识 `-rw-r--r--`、用户名、文件大小（人类可读）、上传时间（`MMM dd HH:mm` 格式）、文件名

#### Scenario: 无文件时的空状态
- **GIVEN** 用户尚未上传任何文件
- **WHEN** 用户进入文件管理页面
- **THEN** 显示空状态提示："暂无文件，请上传文件。"

#### Scenario: 加载失败
- **GIVEN** API 请求失败（网络错误、500 等）
- **WHEN** 用户进入文件管理页面或刷新列表
- **THEN** 显示错误提示，提供"重试"按钮

### Requirement: 文件下载（FR-04 下载部分）
系统 SHALL 支持用户点击文件名触发文件下载，将文件保存到本地。

#### Scenario: 点击文件名下载
- **GIVEN** 文件列表中有文件
- **WHEN** 用户点击文件名
- **THEN** 触发浏览器下载，文件保存到本地

#### Scenario: 下载失败处理
- **GIVEN** 文件下载 API 返回错误
- **WHEN** 用户点击文件名
- **THEN** 显示"下载失败"错误提示

### Requirement: 文件删除（FR-03 删除部分）
系统 SHALL 支持用户删除不再需要的文件，删除前弹出二次确认对话框。

#### Scenario: 确认删除文件
- **GIVEN** 文件列表中有文件
- **WHEN** 用户点击删除按钮 → 弹出确认对话框 → 用户点击"确认删除"
- **THEN** 文件从列表中移除，显示"已删除"成功提示

#### Scenario: 取消删除操作
- **GIVEN** 文件列表中有文件
- **WHEN** 用户点击删除按钮 → 弹出确认对话框 → 用户点击"取消"
- **THEN** 对话框关闭，文件保持不变

#### Scenario: 删除失败处理
- **GIVEN** 删除 API 返回错误
- **WHEN** 用户确认删除
- **THEN** 显示"删除失败"错误提示，文件保留在列表中

### Requirement: 文件上传（FR-05）
系统 SHALL 支持在文件管理页面上传新文件，提供拖拽区域和点击上传两种方式。

#### Scenario: 点击上传文件
- **GIVEN** 用户在文件管理页面
- **WHEN** 用户点击上传区域 → 选择文件 → 确认
- **THEN** 文件上传成功 → 列表自动刷新 → 显示"上传成功"提示

#### Scenario: 拖拽上传文件
- **GIVEN** 用户在文件管理页面
- **WHEN** 用户拖拽文件到上传区域 → 释放
- **THEN** 文件上传成功 → 列表自动刷新

#### Scenario: 上传超大文件
- **GIVEN** 用户选择超过 10 MiB 的文件
- **WHEN** 用户尝试上传
- **THEN** 显示"文件大小超过限制（最大 10 MiB）"错误提示

#### Scenario: 上传不支持的文件类型
- **GIVEN** 用户选择不支持的文件类型
- **WHEN** 用户尝试上传
- **THEN** 显示"不支持的文件类型"错误提示

### Requirement: 安全隔离（FR-06, FR-07）
系统 SHALL 确保用户仅能查看和操作自己上传的文件，后端通过 `X-User-Id` header 进行身份校验。

#### Scenario: 用户只能看到自己的文件
- **GIVEN** 用户 A 上传了文件 F1，用户 B 上传了文件 F2
- **WHEN** 用户 A 访问文件管理页面
- **THEN** 只显示 F1，不显示 F2

#### Scenario: 越权删除被拦截
- **GIVEN** 用户 A 尝试删除用户 B 的文件（通过篡改请求）
- **WHEN** 发送 DELETE 请求
- **THEN** 后端返回 403 或 404，文件不被删除
