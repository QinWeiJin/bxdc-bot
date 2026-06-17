## ADDED Requirements

### Requirement: conversations 表维护 enabled_files 字段
conversations 表 SHALL 新增 `enabled_files` JSON 列，类型为 `JSON DEFAULT NULL`，存储该会话可操作的文件 ID 列表，格式与 `enabled_skills` 一致：`[1, 3, 5]`。

#### Scenario: 创建新对话时 enabled_files 默认为空
- **WHEN** 用户创建新对话
- **THEN** `enabled_files` 默认为空数组 `[]`（新对话无默认文件权限）

#### Scenario: 存量对话 enabled_files 兼容
- **WHEN** 查询存量对话（升级前创建的）
- **THEN** `enabled_files` 为 NULL，此时文件工具不做过滤（全量可用，向后兼容）

### Requirement: 文件上传自动绑定到当前对话
当前端上传文件并携带 `conversationId` 时，`FileUploadController` SHALL 在上传成功后自动将该文件的 ID 追加到对应对话的 `enabled_files` 列表。

#### Scenario: 带 conversationId 上传文件
- **WHEN** 用户上传文件 `report.xlsx` 到对话 `uuid-A`，请求中携带 `conversationId=uuid-A`
- **THEN** 文件 `report.xlsx` 保存后，其 `fileId` 被自动追加到对话 `uuid-A` 的 `enabled_files` 中

#### Scenario: 不带 conversationId 上传文件（旧客户端兼容）
- **WHEN** 用户上传文件且请求中无 `conversationId`
- **THEN** 文件正常保存，但不绑定到任何对话

### Requirement: file_list 按 enabled_files 过滤
`file_list` 工具 SHALL 仅返回当前会话 `enabled_files` 列表中的文件。当 `enabled_files` 为 NULL 时（存量对话），返回用户全部文件。

#### Scenario: 有 enabled_files 时过滤
- **WHEN** 对话 `uuid-A` 的 `enabled_files = [5, 8]`，LLM 在该对话中调用 `file_list`
- **THEN** 只返回 fileId 为 5 和 8 的文件，不返回其他用户文件

#### Scenario: enabled_files 为空时返回空列表
- **WHEN** 对话的 `enabled_files = []`，LLM 调用 `file_list`
- **THEN** 返回空文件列表 + 提示信息

#### Scenario: 创建新对话后调 file_list 返回空
- **WHEN** 用户刚创建对话（`enabled_files = []`）后 LLM 立即调 `file_list`
- **THEN** 返回空列表 + 提示用户先上传文件或到配置面板勾选文件

#### Scenario: enabled_files 为 NULL 时全量返回
- **WHEN** 存量对话的 `enabled_files` 为 NULL
- **THEN** `file_list` 返回用户全部文件（与升级前行为一致）

### Requirement: file_delete 和 file_detail 增加会话归属校验
`file_delete` 和 `file_detail` 工具 SHALL 在现有 `userId` 校验之上，增加会话 `enabled_files` 归属校验。文件不在当前会话的 `enabled_files` 中时，返回错误并提示该文件不在当前会话权限内。

#### Scenario: 删除会话内文件
- **WHEN** 对话 `uuid-A` 的 `enabled_files = [5, 8]`，LLM 在该对话中调用 `file_delete` 删除 fileId=5
- **THEN** 正常执行删除流程（含二次确认）

#### Scenario: 删除会话外文件被拒绝
- **WHEN** 对话 `uuid-A` 的 `enabled_files = [5, 8]`，LLM 尝试删除 fileId=12（不在列表中）
- **THEN** 返回错误："文件(12)不在当前会话权限内，请使用 file_list 查看可用文件"

#### Scenario: 存量对话无限制
- **WHEN** 存量对话的 `enabled_files` 为 NULL
- **THEN** `file_delete`/`file_detail` 仅校验 `userId`，行为与升级前一致

### Requirement: file_clear_all 行为变更
`file_clear_all` 工具 SHALL 仅清空当前会话 `enabled_files` 中的文件，而非用户全部文件。当 `enabled_files` 为 NULL 时，维持原有行为（清空全部）。

#### Scenario: 清空当前会话文件
- **WHEN** 对话 `uuid-A` 的 `enabled_files = [5, 8]`，LLM 调用 `file_clear_all` 并确认
- **THEN** 仅删除 fileId 为 5 和 8 的文件，用户其他文件不受影响

#### Scenario: 存量对话清空全部
- **WHEN** 存量对话的 `enabled_files` 为 NULL
- **THEN** `file_clear_all` 清空用户全部文件（与升级前一致）

#### Scenario: enabled_files 为空时无需清空
- **WHEN** 对话的 `enabled_files = []`，LLM 调用 `file_clear_all`
- **THEN** 返回成功消息"当前会话无文件，无需清空"，不执行任何删除

### Requirement: 前端会话配置面板扩展
`ConversationSidebar` SHALL 将"Skill配置"按钮改为"配置"。弹出的配置面板 SHALL 扩展为 Tab 布局，包含"Skill"和"文件"两个 Tab。文件 Tab SHALL 以 checkbox 列表展示用户全部文件，支持勾选/取消，保存时通过 `PUT /api/conversations/{id}` 更新 `enabled_files`。

#### Scenario: 文件 Tab 勾选
- **WHEN** 用户打开对话 `uuid-A` 的配置面板，切换到"文件"Tab
- **THEN** 展示用户全部文件列表，当前 `enabled_files` 中的文件 checkbox 已勾选

#### Scenario: 保存文件配置
- **WHEN** 用户在文件 Tab 勾选 fileId=12，点击保存
- **THEN** 调用 `PUT /api/conversations/{uuid-A}` 传入 `enabled_files: [5, 8, 12]`，关闭面板

#### Scenario: 跨会话共享文件
- **WHEN** 用户分别在对话 A 和对话 B 的配置面板中勾选同一个文件 `report.xlsx`
- **THEN** 两个对话的 `enabled_files` 各自独立包含该 fileId，互不影响
