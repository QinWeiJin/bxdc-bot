# file-upload-frontend

## Purpose

前端文件上传模块（对应需求方案 A1 模块二）提供统一的上传入口、四类校验、三种交互方式（点击 / 拖拽 / 粘贴）、loading + 取消交互。本 change **仅实现前端**，后端存储属于模块一/模块三，不在本 spec 范围。

## Requirements

### Requirement: 三种上传入口

系统 SHALL 提供三种上传文件方式，**全部走同一个 composable `useFileUpload`**：

1. 图标点击上传
2. 拖拽上传到聊天输入框
3. `Ctrl+V` 粘贴上传

#### Scenario: 点击图标触发文件选择器
- **WHEN** 用户点击聊天输入框附近的上传图标
- **THEN** 系统 SHALL 唤起浏览器的文件选择器（`type="file"`，`multiple`）
- **AND** 用户选择文件后 SHALL 自动开始上传流程（无需点击额外的"上传"按钮）

#### Scenario: 拖拽文件到输入框
- **WHEN** 用户将一个或多个文件拖入聊天输入框区域
- **THEN** 系统 SHALL 阻止浏览器默认行为（页面跳转）
- **AND** SHALL 自动开始上传流程

#### Scenario: Ctrl+V 粘贴文件
- **WHEN** 用户在聊天输入框按 `Ctrl+V` 且剪贴板内含文件（如截图、复制文件）
- **THEN** 系统 SHALL 从 `clipboardData.files` 提取文件
- **AND** SHALL 自动开始上传流程

### Requirement: 4 步校验（弹窗提示）

每次上传 SHALL 按以下顺序执行 4 步校验，**任一失败弹窗提示后即终止该文件**。

#### Scenario: 类型校验
- **WHEN** 上传文件扩展名不在 `FILE_UPLOAD_CONFIG.allowedExtensions[fileType]` 中
- **THEN** 弹窗提示文案为 "当前仅支持doc、docx、xls、xlsx、csv、txt以及md文件的上传"

#### Scenario: 大小校验
- **WHEN** 单文件大小超过 `FILE_UPLOAD_CONFIG.maxFileSizeMib[fileType]`（默认 10 MiB）
- **THEN** 弹窗提示文案为 "文件大小超过10Mb，请修改后重试。"

#### Scenario: 数量校验
- **WHEN** 当前 session 已上传文件数 + 本次新文件数 > `FILE_UPLOAD_CONFIG.maxFilesPerSession`（默认 5）
- **THEN** 弹窗提示文案为 "单次最多上传5个文件，请减少选择。"

#### Scenario: 重复校验（替换或取消）
- **WHEN** 上传文件名与当前 session 内已有文件名重复
- **THEN** 弹窗询问 "该文件已于{time}上传，是否进行替换？"，带「替换」「取消」两个按钮
- **AND** 选择「替换」SHALL 删除旧记录并添加新文件
- **AND** 选择「取消」SHALL 跳过该文件，继续处理后续文件

### Requirement: 上传状态机

每个上传中的文件 SHALL 处于以下 5 个状态之一：

```
pending → parsing → done
                ↘  failed
                ↘  cancelled（用户点击 ×）
```

#### Scenario: 解析中显示 loading
- **WHEN** 文件状态为 `parsing`
- **THEN** 组件 SHALL 显示加载动画（转圈）

#### Scenario: 解析完成变色
- **WHEN** 文件状态从 `parsing` 变为 `done`
- **THEN** 文件标识 SHALL 由灰色变为绿色（成功主题色），loading 动画消失

#### Scenario: 解析失败变红
- **WHEN** 文件状态为 `failed`
- **THEN** 文件标识 SHALL 变为红色（错误主题色），并展示错误信息

### Requirement: 取消上传

#### Scenario: 点击 × 取消未完成上传
- **WHEN** 文件状态为 `pending` 或 `parsing` 且用户点击 `×` 按钮
- **THEN** 关联的 `AbortController` SHALL 被触发，停止解析
- **AND** 文件状态变为 `cancelled`
- **AND** 文件 SHALL 不再展示在对话附件区

### Requirement: 限额配置

`FILE_UPLOAD_CONFIG` SHALL 严格按以下值配置（与需求方案 A1 对齐）：

```ts
{
  maxFileSizeMib: { word: 10, excel: 10, ppt: 10, txt: 10, image: 10 },
  maxFilesPerSession: 5,
  allowedExtensions: {
    word: ['.doc', '.docx'],
    excel: ['.xls', '.xlsx', '.csv'],
    ppt: ['.ppt', '.pptx'],
    txt: ['.txt', '.md', '.py'],
    image: ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg'],
  },
  unsupportedMessage: '当前仅支持doc、docx、xls、xlsx、csv、txt以及md文件的上传',
  fileTooLargeMessage: '文件大小超过10Mb，请修改后重试。',
  tooManyFilesMessage: '单次最多上传5个文件，请减少选择。',
}
```

#### Scenario: csv 文件被接受为 excel 类型
- **WHEN** 上传 `data.csv` 且 `fileType === 'excel'`
- **THEN** 类型校验通过
- **AND** SHALL 走 csv 解析（本期暂用 txt fallback，后续由启雷模块实现完整 csv 解析）

#### Scenario: py 文件被接受为 txt 类型
- **WHEN** 上传 `script.py` 且 `fileType === 'txt'`
- **THEN** 类型校验通过
- **AND** 走原生 `FileReader` 读取纯文本

### Requirement: 解析后自动注入消息输入

#### Scenario: 解析完成后填充到输入框
- **WHEN** 文件解析完成（`status === 'done'`）
- **THEN** composable SHALL 通过 `onParsed` 回调通知上层
- **AND** 上层组件 SHALL 将解析得到的纯文本追加到聊天输入框
- **AND** 文件标识 SHALL 展示在附件区

### Requirement: 三种错误情况的中文提示

系统 SHALL 统一使用 `ElMessageBox.alert` 弹窗，且每种错误使用需求方案 A1 给定的精确文案（不要修改、合并或省略）。

#### Scenario: 弹窗类型
- **WHEN** 任一校验失败
- **THEN** SHALL 使用 `ElMessageBox.alert` 而非 `ElMessage`（消息提示）
- **AND** 弹窗标题为 "上传提示"
- **AND** 弹窗类型为 `warning`
