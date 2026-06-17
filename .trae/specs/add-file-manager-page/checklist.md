# Checklist: 文件管理页面

## 功能完整性

- [x] 导航栏「文件管理」入口可见，图标为 FolderOpenIcon
- [x] 点击导航入口跳转到 `/file-manager`，菜单项高亮
- [x] 未登录访问 `/file-manager` 重定向到 `/login`
- [x] 刷新页面后路由保持，菜单高亮正确

## 文件列表展示

- [x] 文件列表以终端 `ls -l` 风格渲染（深色背景、等宽字体）
- [x] 每行包含：权限标识 `-rw-r--r--`、用户名、文件大小（人类可读）、上传时间、文件名
- [x] 文件名为可点击链接，触发下载
- [x] 每行末尾有下载图标按钮和删除图标按钮
- [x] 无文件时显示空状态提示
- [x] API 加载中显示 loading 状态
- [x] API 加载失败显示错误信息 + 重试按钮

## 文件上传

- [x] 上传区域可见（点击可用，拖拽 label 区域也可触发系统文件选择）
- [x] 选择有效文件后上传成功，列表自动刷新
- [x] 超过 10 MiB 文件被拦截，显示错误提示
- [x] 不支持的文件类型被拦截，显示错误提示
- [x] 上传成功后显示 Toast 提示

## 文件下载

- [x] 点击文件名触发浏览器下载
- [x] 下载失败显示错误 Toast

## 文件删除

- [x] 点击删除图标弹出二次确认对话框
- [x] 确认后文件从列表移除，Toast 提示"已删除"
- [x] 取消后文件保持不变
- [x] 删除失败显示错误 Toast

## 安全隔离

- [x] 用户 A 只能看到自己上传的文件（后端 FileUploadController 通过 X-User-Id + FileAccessInterceptor 隔离）
- [x] 用户 B 只能看到自己上传的文件（后端 FileUploadController 通过 X-User-Id + FileAccessInterceptor 隔离）
- [x] 越权删除操作被后端拦截（deleteFile 会校验文件归属）

## 构建与代码质量

- [x] `cd frontend && npx vue-tsc -b` 零错误
- [x] `cd frontend && npm run build` 零错误（exit 0）
- [x] 后端零改动（FileUploadController 无需修改）
- [x] 无新增第三方 npm 包
- [x] 无新增环境变量
- [x] 代码符合 JDK 1.8 规范（仅涉及前端，不适用）
- [x] 修复预存 MessageList.vue:92 TS2322 类型错误

## 用户体验

- [x] 操作反馈及时（Toast / loading 状态）
- [x] 终端风格视觉一致（颜色、字体、间距）
- [ ] 页面加载时间合理（首次 < 3 秒需运行时验证）
