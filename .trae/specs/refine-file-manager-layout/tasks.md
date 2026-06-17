# Tasks: 文件管理页面上传按钮下移 + 分页

## T1: 上传按钮移至底部居中 + 添加分页
**类型**: 前端 | **优先级**: P0 | **依赖**: 无

### 步骤
- [x] 将 `<t-button>` 从 `page-header-right` 移至页面底部：
  - 在 `</t-table>` 后添加 `<div class="bottom-bar">` 包含 upload 按钮 + hidden input
  - 从 `page-header-right` 移除上传按钮和 hidden input
  - `page-header` 保留标题 + 文件计数
- [x] 空状态内的上传引导按钮保留不变
- [x] `t-table` 添加分页属性：`:pagination="files.length > 10 ? { defaultPageSize: 10, pageSizeOptions: [10, 20, 50] } : false"`
- [x] 底部栏样式：`display:flex; justify-content:center; padding:16px 0; border-top:1px solid var(--td-component-stroke)`

### 变更文件
- `frontend/src/views/FileManagerView.vue`

### 验收标准
- 上传按钮在页面底部居中显示
- 空状态页面上传引导按钮仍存在
- 文件 ≤ 10 不分页
- 文件 > 10 显示分页控件，每页 10 条

---

## T2: 构建验证
**类型**: 前端 | **优先级**: P0 | **依赖**: T1

### 步骤
- [x] `cd frontend && npx vue-tsc -b` 零错误
- [x] `cd frontend && npm run build` 零错误（exit 0）

### 验收标准
- vue-tsc -b exit 0
- npm run build exit 0

---

# Task Dependencies
- T2 依赖 T1
