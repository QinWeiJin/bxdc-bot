# memory-initialization-flow Specification (delta)

## ADDED Requirements

### Requirement: 资料编辑页提供记忆清空与重新初始化入口

在用户已登录的「资料编辑」弹窗中，**头像 emoji 选择器下方** MUST 渲染一个**视觉上不显眼但语义可发现**的「清除记忆」文字入口。入口样式 MUST 避免使用主色 / 边框 / 突出背景，仅 hover 或聚焦时才出现危险色提示，以降低误操作概率。

#### Scenario: 入口可见条件
- **WHEN** `MEM0_ENABLED=true`（后端配置）且当前用户已登录
- **THEN** 资料编辑弹窗的 emoji 选择器下方显示「清除记忆」文字入口
- **AND** 入口可点击 / 可键盘聚焦

#### Scenario: 入口隐藏条件
- **WHEN** `MEM0_ENABLED=false`
- **THEN** 资料编辑弹窗**不**渲染「清除记忆」入口（不是 disabled / 灰显，是**完全不出现**）

#### Scenario: 入口位置
- **WHEN** 用户打开资料编辑弹窗
- **THEN** 「清除记忆」入口 MUST 位于「头像」section 内部、emoji 网格**下方**
- **AND** 必须在「保存」/「取消」按钮（footer）**上方**

### Requirement: 清除记忆操作需要二次危险确认

点击「清除记忆」入口 MUST 弹出一个独立的高危确认弹窗，弹窗 MUST 通过显眼的危险样式 + 文案告知用户此操作不可恢复，并 MUST 要求用户主动点「确认清除」按钮才执行，不接受默认确认 / 回车即执行。

#### Scenario: 危险确认弹窗
- **WHEN** 用户点击「清除记忆」入口
- **THEN** 系统弹出独立确认弹窗
- **AND** 弹窗标题 MUST 包含「清除记忆」字样并用 danger 主题（红色）
- **AND** 弹窗正文 MUST 明确写出「此操作不可恢复」并简短说明「将删除该用户的全部长期记忆」

#### Scenario: 二次确认
- **WHEN** 危险确认弹窗弹出
- **THEN** 系统 MUST 提供「取消」与「确认清除」两个按钮
- **AND** 「确认清除」按钮 MUST 使用 danger 主题
- **AND** 弹窗**不**允许通过按 `Enter` / `Esc` / 点击遮罩直接执行清除（必须点「确认清除」按钮）

#### Scenario: 取消清除
- **WHEN** 用户在危险确认弹窗点击「取消」或关闭弹窗
- **THEN** 系统 MUST NOT 调用 mem0 删除接口
- **AND** 弹窗关闭，状态回到资料编辑页

#### Scenario: 确认清除触发删除请求
- **WHEN** 用户在危险确认弹窗点击「确认清除」
- **THEN** 系统 MUST 调用 `POST /memory/delete` 携带当前用户 `userid`
- **AND** 调用期间「确认清除」按钮 MUST 显示 loading 状态且不可重复点击

### Requirement: 清除成功后自动弹出初始化记忆表单

mem0 删除接口返回成功（HTTP 200 且 `code === 200`）后，系统 MUST 自动弹出「初始化记忆」表单弹窗，让用户填写「我是谁」和「我的兴趣爱好 / 性格特征」两个字段，作为新一轮记忆的种子。

#### Scenario: 清除成功后弹出初始化表单
- **WHEN** `POST /memory/delete` 返回成功
- **THEN** 系统 MUST 自动弹出初始化记忆表单
- **AND** 表单 MUST 包含「我是谁」多行文本字段（必填，至少 1 字符，最多 500 字符）
- **AND** 表单 MUST 包含「我的兴趣爱好 / 性格特征」多行文本字段（可选，0–500 字符）
- **AND** 表单 MUST 提供「稍后再说」与「保存初始化」两个按钮

#### Scenario: 提交初始化
- **WHEN** 用户在初始化表单填写「我是谁」（非空）后点击「保存初始化」
- **THEN** 系统 MUST 把「我是谁」+ 「我的兴趣爱好 / 性格特征」按如下规则拼接成单条 `sentencein`：
  - 拼接模板：`"{who}。{hobbies}"`，当「我的兴趣」为空时省略句号和后半段
- **AND** 系统 MUST 调用 `POST /memory/add` 携带 `{ userId, text: <上述拼接结果>, role: 'user' }`
- **AND** 调用期间「保存初始化」按钮 MUST 显示 loading 状态

#### Scenario: 初始化成功
- **WHEN** `POST /memory/add` 返回成功
- **THEN** 系统 MUST 弹出成功提示「记忆初始化更新成功」
- **AND** 系统 MUST 关闭初始化表单弹窗
- **AND** 系统 MUST 关闭资料编辑弹窗，回到调用前的页面

#### Scenario: 初始化失败但清除已成功
- **WHEN** `POST /memory/delete` 成功 + `POST /memory/add` 失败
- **THEN** 系统 MUST 弹出警告提示「记忆已清空但初始化失败：{error}，请重试」
- **AND** 初始化表单 MUST 保持打开，用户可编辑后再次点「保存初始化」

#### Scenario: 清除失败
- **WHEN** `POST /memory/delete` 失败（HTTP 非 2xx 或 `code !== 200`）
- **THEN** 系统 MUST 弹出错误提示「清空失败：{error}」
- **AND** 系统 MUST NOT 弹出初始化表单
- **AND** 资料编辑弹窗 MUST 保持打开

#### Scenario: 用户在初始化表单选择「稍后再说」
- **WHEN** 用户在初始化表单点击「稍后再说」
- **THEN** 系统 MUST 关闭初始化表单弹窗
- **AND** 系统 MUST 关闭资料编辑弹窗
- **AND** 此时 mem0 中该用户的记忆处于「已清空」状态（不自动恢复）

### Requirement: 记忆开关在配置文件中管理

「清除记忆」入口的可见性 MUST 由后端配置文件 `agent-core/.env` 的 `MEM0_ENABLED` 控制，**不**允许前端通过构建时环境变量、localStorage 或其它方式绕过此开关。

入口渲染**只**由 `MEM0_ENABLED` 决定，**不**查询 mem0 服务是否有记忆（即没有 `hasMemory` 概念，默认都认为有记忆、入口始终按 enabled 渲染）。

#### Scenario: 后端通过 status 接口暴露开关
- **WHEN** 前端调用 `GET /memory/status?userId=<currentUserId>`
- **THEN** 后端 MUST 读取 `process.env.MEM0_ENABLED` 并返回 `{ enabled: <boolean> }`（**只**有 enabled，**没有** hasMemory 字段）
- **AND** `enabled` MUST 与 `MEM0_ENABLED` 同步；`MEM0_ENABLED` 为 false/0/off/no/disabled 时 `enabled` MUST 为 false

#### Scenario: mem0 不可达时入口不显示
- **WHEN** `/memory/status` 内部判断 mem0 服务不可达（任何异常）
- **THEN** 后端 MUST 返回 `{ enabled: false }`（不抛错）
- **AND** 前端 MUST 不渲染「清除记忆」入口

#### Scenario: 前端按 status 渲染入口
- **WHEN** 前端打开资料编辑弹窗
- **THEN** 前端 MUST 调用 `GET /memory/status?userId=<currentUserId>`
- **AND** 仅当响应 `enabled === true` 时渲染「清除记忆」入口
- **AND** `enabled === false` 时**不**发起后续任何 `delete` / `add` 调用

### Requirement: 清除与初始化操作按当前登录用户隔离

`POST /memory/delete` 与 `POST /memory/add` MUST 仅作用于**当前已登录用户的 `userid`**，不允许通过请求体 / 查询参数指定其他用户；后端 MUST 校验请求中的 `userId` 与当前 session 的用户一致。

**注**：现有 `POST /memory/add` 端点**没有**这个守卫，是已知安全 bug。本次 change 顺手修复（与 delete 端点同时加固），在 spec 里一并锁定。

#### Scenario: 不允许跨用户删除
- **WHEN** 请求体中 `userId` 与当前 session 用户不一致
- **THEN** 后端 MUST 返回 403 或 400
- **AND** MUST NOT 调用 mem0 `/deletemem`

#### Scenario: 不允许跨用户写入
- **WHEN** 请求体中 `userId` 与当前 session 用户不一致
- **THEN** 后端 MUST 返回 403 或 400
- **AND** MUST NOT 调用 mem0 `/madd`

#### Scenario: 现有 add 端点守卫补强
- **WHEN** 本次 change 部署
- **THEN** `POST /memory/add` MUST 加上与 `POST /memory/delete` 同样的 userId 守卫
- **AND** 守卫失败 MUST 返回 403
- **AND** MUST 不影响前端已有调用（前端当前传的是当前用户 userId，守卫通过）
