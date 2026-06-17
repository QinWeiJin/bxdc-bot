## ADDED Requirements

### Requirement: 环境预检
测试脚本 SHALL 在开始测试前执行环境预检，确保所有依赖服务可用。

#### Scenario: 服务端口检测
- **WHEN** 测试脚本启动
- **THEN** 脚本检测 localhost:5173、3000、18080、3456、3306 端口是否可达
- **AND** 任一不可达时打印错误信息并退出

#### Scenario: agent-browser 可用性检测
- **WHEN** 测试脚本启动
- **THEN** 脚本执行 `agent-browser doctor` 确认 CLI 可用
- **AND** 不可用时打印安装提示并退出

#### Scenario: SSH 台账预置
- **WHEN** 测试前 SSH Skill 所需服务器台账不存在
- **THEN** 脚本通过 API 创建台账记录（IP: 39.104.81.41, 用户: root）
- **AND** 创建失败时跳过 SSH Skill 测试步骤

#### Scenario: Mock 服务预置
- **WHEN** 测试前需要 Mock API 接口
- **THEN** 脚本确认 Mock 服务（`:3456`）可访问
- **AND** 不可用时跳过依赖 Mock 的测试步骤

### Requirement: 登录流程
测试脚本 SHALL 验证用户输入 ID 后能成功进入聊天界面。

#### Scenario: 打开登录页面
- **WHEN** 执行 `agent-browser open http://localhost:5173`
- **THEN** 页面跳转至 `/login`
- **AND** snapshot 包含 "输入您的 ID 以访问账户" 文本

#### Scenario: 输入用户 ID 并登录
- **WHEN** 在 textbox 输入 "000000"
- **AND** 点击 "登录" 按钮
- **AND** 等待 3 秒
- **THEN** 页面进入聊天界面
- **AND** snapshot 包含 "新建对话" 按钮
- **AND** snapshot 包含用户昵称 "蛋蛋"

### Requirement: 欢迎语展示
测试脚本 SHALL 验证新对话中欢迎语正确展示。

#### Scenario: 进入聊天后显示欢迎语
- **WHEN** 登录成功后页面进入聊天界面
- **THEN** 聊天区域显示欢迎语（包含用户昵称 "蛋蛋" 和头像 emoji）
- **AND** 欢迎语文本长度 > 5 个字符（非空）

### Requirement: 新建对话
测试脚本 SHALL 验证新建对话功能正常。

#### Scenario: 点击新建对话按钮
- **WHEN** 点击 "新建对话" 按钮
- **AND** 等待 3 秒
- **THEN** sidebar 中出现 "新对话" 条目（刚刚创建）
- **AND** 聊天区显示新的欢迎语

### Requirement: Compute Skill 执行
测试脚本 SHALL 验证内置 compute Skill 能正确执行数学计算。

#### Scenario: 发送计算请求并验证结果
- **WHEN** 在输入框输入 "帮我算一下 250 乘以 4 等于多少"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 90 秒）
- **THEN** AI 回复中包含 "1000"
- **AND** snapshot 包含 `compute` Skill 调用记录
- **AND** Skill 状态为 "已完成" 且有 ✓ 标记

#### Scenario: 对话自动命名
- **WHEN** 发送消息后 AI 响应完成
- **THEN** sidebar 中该对话名称更新为非 "新对话" 的有意义名称

### Requirement: API Skill GET 执行（Query 参数）
测试脚本 SHALL 验证已存在的扩展 API Skill（聚合新闻 GET + Query 参数）能正确调用外部接口。

#### Scenario: 发送新闻查询请求
- **WHEN** 在输入框输入 "帮我查一下头条新闻，要国内的最新几条"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 90 秒）
- **THEN** AI 回复中包含新闻标题或 "新闻" 相关内容
- **AND** snapshot 包含扩展 Skill `juhe_news_query` 调用记录

### Requirement: Skill 生成器 — 创建 POST JSON Body API Skill
测试脚本 SHALL 验证 skill_generator 能通过自然语言对话创建新的 API Skill（POST + JSON Body），并立即执行该 Skill 验证正确性。

#### Scenario: 通过对话创建 POST JSON Body Skill
- **WHEN** 在输入框输入 "帮我创建一个技能，名字叫 mock_test_api，用于调用 POST http://localhost:3456/mock/test/normal 接口，请求方式是 POST，Content-Type 是 application/json，请求体是 JSON 格式，有一个参数 name，类型是 string"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 120 秒）
- **THEN** AI 回复中包含 Skill 创建成功的提示
- **AND** Skill 列表中出现 `mock_test_api`

#### Scenario: 执行新创建的 POST JSON Body Skill
- **WHEN** 在输入框输入 "用 mock_test_api 帮我发一个请求，name 填 test_user"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 90 秒）
- **THEN** AI 回复中包含 mock_test_api 调用结果
- **AND** snapshot 包含 `mock_test_api` Skill 调用记录

### Requirement: API Skill 表单提交（formBody）
测试脚本 SHALL 验证扩展 API Skill 的 formBody 参数绑定模式能正确发送表单请求（可复用 skill_generator 创建 form-body 类型 Skill）。

#### Scenario: 通过对话创建 formBody Skill
- **WHEN** 在输入框输入 "帮我创建一个技能，名字叫 mock_form_api，POST 请求 http://localhost:3456/mock/test/normal，参数绑定方式是 formBody"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 120 秒）
- **THEN** AI 回复中包含 Skill 创建成功的提示

#### Scenario: 执行 formBody Skill
- **WHEN** 在输入框输入 "用 mock_form_api 发送请求，name 填 form_user"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 90 秒）
- **THEN** AI 回复中包含 mock_form_api 调用结果

### Requirement: 异步轮询 API 任务
测试脚本 SHALL 验证异步轮询任务能正确提交并轮询到完成状态。

#### Scenario: 通过对话触发异步轮询任务
- **WHEN** 在输入框输入 "帮我创建一个异步轮询技能，名字叫 async_export_poll，POST 提交到 http://localhost:3456/mock-async/export/submit，然后用 GET http://localhost:3456/mock-async/export/status?task_id={task_id} 轮询状态"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 120 秒）
- **THEN** AI 回复中包含 Skill 创建成功的提示

#### Scenario: 执行异步轮询任务并验证完成
- **WHEN** 在输入框输入 "用 async_export_poll 提交一个导出任务"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 120 秒）
- **THEN** AI 回复中包含任务执行结果或完成状态
- **AND** snapshot 包含 `async_export_poll` Skill 调用记录

### Requirement: 长时间返回异步任务（SINGLE_CALL）
测试脚本 SHALL 验证长时间返回的异步任务能正确提交并立即返回 SINGLE_CALL 状态。

#### Scenario: 创建长时间 API Skill
- **WHEN** 在输入框输入 "帮我创建一个技能，名字叫 long_async_task，POST 请求 http://localhost:3456/mock/api/user/create，参数 username 和 type，这个接口返回时间较长需要异步处理"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 120 秒）
- **THEN** AI 回复中包含 Skill 创建成功的提示

#### Scenario: 执行长时间异步任务
- **WHEN** 在输入框输入 "用 long_async_task 创建一个用户，username 填 test123，type 填 admin"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 30 秒，因为返回是 SINGLE_CALL 立即返回）
- **THEN** AI 回复中包含任务已提交或异步任务 ID
- **AND** 消息通知区域可出现任务完成通知

### Requirement: Template 类任务执行
测试脚本 SHALL 验证 Template 类型 Skill 能正确返回预设提示词内容。

#### Scenario: 通过对话创建 Template Skill
- **WHEN** 在输入框输入 "帮我创建一个模板技能，名字叫 greeting_template，提示词内容是：请用中文回复用户的问候，回复格式是 JSON，包含 greeting 和 message 两个字段"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 120 秒）
- **THEN** AI 回复中包含 Skill 创建成功的提示

#### Scenario: 执行 Template Skill
- **WHEN** 在输入框输入 "用 greeting_template 对用户小明说一句话"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 60 秒）
- **THEN** AI 回复中包含 JSON 格式的问候（含 greeting 和 message 字段）
- **AND** snapshot 包含 `greeting_template` Skill 调用记录

### Requirement: 自主规划（OPENCLAW）Skill 生成与执行
测试脚本 SHALL 验证自主规划 Skill 能正确编排多个子工具并按序执行。

#### Scenario: 通过对话创建 OPENCLAW Skill
- **WHEN** 在输入框输入 "帮我创建一个自主规划技能，名字叫 compute_and_news，系统提示词是：首先用 compute 工具计算 100 加 200 的结果，然后用 juhe_news_query 查询一条科技类新闻，最后将计算结果和新闻标题一起返回。允许工具列表填写 compute 和 juhe_news_query，编排模式选串行"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 120 秒）
- **THEN** AI 回复中包含 Skill 创建成功的提示

#### Scenario: 执行 OPENCLAW Skill
- **WHEN** 在输入框输入 "执行 compute_and_news"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 120 秒）
- **THEN** AI 回复中包含计算结果（300）和新闻标题
- **AND** 子工具调用轨迹中包含 compute 和 juhe_news_query

### Requirement: SSH Skill 生成与执行
测试脚本 SHALL 验证 skill_generator 能通过对话创建 SSH 类型 Skill 并执行远程命令。

#### Scenario: 通过对话创建 SSH Skill
- **WHEN** 在输入框输入 "帮我创建一个 SSH 技能，名字叫 ssh_disk_check，用来查看服务器磁盘使用情况，执行命令是 df -h。可以用的服务器台账别名是 39.104.81.41"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 120 秒）
- **THEN** AI 回复中包含 Skill 创建成功的提示

#### Scenario: 执行 SSH Skill
- **WHEN** 在输入框输入 "用 ssh_disk_check 查看服务器磁盘使用情况"
- **AND** 点击发送
- **AND** 等待 AI 响应完成（最长 90 秒）
- **THEN** AI 回复中包含磁盘使用信息（如 "df" 或 "磁盘" 或容量数字）
- **AND** snapshot 包含 `ssh_disk_check` Skill 调用记录
- **AND** 如 SSH 服务器不可用则标记为 SKIP 而非 FAIL

### Requirement: 对话切换
测试脚本 SHALL 验证对话切换功能正常。

#### Scenario: 切换回之前的历史对话
- **WHEN** 点击 sidebar 中的历史对话条目
- **AND** 等待 2 秒
- **THEN** 聊天区显示该对话的历史消息
- **AND** 包含之前发送的用户消息和 AI 回复

### Requirement: 测试结果汇总
测试脚本 SHALL 在全部步骤完成后输出通过/失败汇总。

#### Scenario: 输出测试报告
- **WHEN** 所有测试步骤执行完毕
- **THEN** 输出每步的 PASS/FAIL/SKIP 状态
- **AND** 输出总体通过率
- **AND** 失败时脚本以非零退出码退出（CI 兼容）
