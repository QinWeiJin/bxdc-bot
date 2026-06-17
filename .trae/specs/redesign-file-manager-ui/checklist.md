# Checklist: 文件管理页面 UI 改版

## 视觉一致性

- [x] 页面不使用命令行/终端风格（无深色背景 `#1e1e1e`、无等宽字体、无 `$` 提示符）
- [x] 使用 TDesign 组件（t-table、t-button、t-popconfirm、t-loading、t-alert 等）
- [x] 使用 TDesign CSS 变量（`--td-*`）作为颜色来源，无硬编码 hex 颜色值
- [x] 页面布局与其他管理页面一致（顶部标题 + 操作栏 + 表格/列表）

## 文件列表展示

- [x] t-table 正确渲染文件列表，包含列：文件名（含类型图标）、类型、大小、上传时间、操作
- [x] 文件类型图标根据扩展名正确区分（word/excel/ppt/pdf/image/txt/other）
- [x] 文件大小列人类可读（B/KB/MB/GB）
- [x] 上传时间格式可读
- [x] 加载中显示 t-loading
- [x] 加载失败显示 t-alert theme="error" + 重试按钮
- [x] 无文件时显示空状态提示

## 文件上传

- [x] "上传文件"按钮可见，点击触发文件选择
- [x] 上传成功列表自动刷新 + MessagePlugin.success
- [x] 超 10 MiB 文件被拦截 + MessagePlugin.error
- [x] 不支持的文件类型被拦截 + MessagePlugin.error
- [x] 上传中使用 MessagePlugin.loading 或按钮 loading 状态

## 文件下载

- [x] 操作列下载按钮触发浏览器下载
- [x] 下载失败时 MessagePlugin.error

## 文件删除

- [x] 操作列删除按钮被 t-popconfirm 包裹
- [x] t-popconfirm 确认后文件从列表移除 + MessagePlugin.success
- [x] t-popconfirm 取消后文件保持
- [x] 删除失败 MessagePlugin.error

## 构建与代码质量

- [x] `cd frontend && npx vue-tsc -b` 零错误
- [x] `cd frontend && npm run build` 零错误（exit 0）
- [x] 无新增第三方 npm 包
- [x] 无新增环境变量
- [x] 仅修改 `frontend/src/views/FileManagerView.vue`，其他文件无改动
