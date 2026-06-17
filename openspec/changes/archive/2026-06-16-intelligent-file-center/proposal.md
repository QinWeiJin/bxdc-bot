## Why

当前 BXDC.bot 平台仅支持文本对话，无法处理用户上传的文件（Word、Excel、CSV、TXT、MD、Python）。运营人员在日常工作中需要频繁分析表格数据、提取文档内容、批量处理文件，但缺乏统一的文件上传、解析、操作和下载能力。本需求构建完整的"智能文件中心"，让用户通过自然语言对话即可完成文件的全生命周期管理。

## What Changes

- **BREAKING**: 新增 6 个能力模块，全部在 skill-gateway (Java, JDK 1.8) 中实现，不修改 agent-core
- 模块一：**用户隔离** — 基于 AAM token 的唯一标识进行文件隔离，FTP 自动创建用户目录
- 模块二：**文件上传** — 支持点击、拖拽、Ctrl+V 三种上传方式，含魔数校验、大小/数量/重复校验、上传进度、取消上传
- 模块三：**智能文件解析** — 根据文件类型自动路由到对应解析器（Word/Excel/CSV/TXT/MD/Python），产出结构化 JSON
- 模块四：**数据处理 API** — 33+ HTTP API 端点（Excel 13 个、Word 6 个、TXT 10 个、MD 11 个），供 agent-core 通过 HTTP 调用
- 模块五：**文件管理** — 对话式文件列表展示、删除（二次确认）、清空、详情查看
- 模块六：**文件下载** — 修改后文件下载 + 主动请求下载

## Capabilities

### New Capabilities
- `file-user-isolation`: 用户隔离 — AAM token 认证、FTP 目录自动创建、跨用户访问拦截
- `file-upload`: 文件上传 — 三种上传方式、四重校验（类型/大小/数量/重复）、进度与取消、FTP 存储
- `file-parsing`: 智能文件解析 — 文件类型路由、各格式解析器、结构化 JSON 产出、py 文件全量解析
- `file-data-processing`: 数据处理 — 33+ HTTP API（Excel/Word/TXT/MD 操作能力），Apache POI + Python 补充
- `file-management`: 文件管理 — 列表/删除/清空/详情查看，对话式操作
- `file-download`: 文件下载 — 修改后文件下载、主动请求下载

### Modified Capabilities
- `api-extension-skill-llm-tool-call`: 新增文件操作 HTTP API 注册为 LLM Tool，新增文件下载 URL 字段

## Impact

- **代码**: 仅 skill-gateway (Java)，新增 Controller/Service/Entity 层，不修改 agent-core
- **依赖**: Apache POI (Excel/Word)、Apache Tika (魔数校验)、FTP 客户端
- **存储**: FTP 服务器按用户目录存储文件
- **协议**: 标准 HTTP REST API（JSON 入参/出参），复用 skill-gateway 现有的 Tool 调用机制
- **前端**: 处理结果通过 Tool Result JSON 传给 LLM 渲染，不修改前端
