# document-parsers

## Purpose

前端文档解析器统一入口（`frontend/src/utils/fileParser.ts`）按文件类型路由到不同的解析实现。本次修改 PPT 分支，从原 `agentFallback`（POST agent-core）改为直接调用 skill-gateway 的 `POST /api/features/file/parse-ppt`。

## Requirements

### Requirement: PPT 解析走 skill-gateway

`fileParser.ts` 处理 `fileType === 'ppt'` 时必须调用 `pptParser.parsePpt(file)`，不再调用 `agentFallback`。

#### Scenario: 上传 .pptx

- **WHEN** fileType === 'ppt' 且 ext === '.pptx'
- **THEN** `parseDocument` 动态 import `pptParser.parsePpt` 并调用
- **AND** 返回 skill-gateway 响应的 `text` 字段

#### Scenario: 上传 .ppt（旧格式）

- **WHEN** fileType === 'ppt' 且 ext === '.ppt'
- **THEN** `parseDocument` 调用同一个 `pptParser.parsePpt`
- **AND** skill-gateway 内部根据扩展名分派 HSLF / XSLF

#### Scenario: skill-gateway 返回 413

- **WHEN** ppt 文件 > 10 MiB
- **THEN** `parsePpt` 抛 `Error('PPT 解析失败：413 ...')`
- **AND** useFileUpload 写入 `errorMessage`，UI 显示「失败」

#### Scenario: skill-gateway 返回 504

- **WHEN** ppt 解析超时
- **THEN** `parsePpt` 抛 `Error('PPT 解析失败：504 ...')`

### Requirement: 路由配置

serve-proxy 必须把 `/features/file/parse-ppt` 路由到 skill-gateway (18080)，而不是 agent-core (3000)。

#### Scenario: 前端 fetch /features/file/parse-ppt

- **WHEN** 浏览器请求 `http://localhost:8080/features/file/parse-ppt`
- **THEN** serve-proxy 转发到 `http://127.0.0.1:18080/api/features/file/parse-ppt`
- **AND** 响应原样回传浏览器

#### Scenario: 其他 /features/* 请求仍走 agent-core

- **WHEN** 浏览器请求 `/features/anything-else`
- **THEN** 仍走 agent-core 3000
- **AND** 不影响其他功能

### Requirement: 兼容旧 doc / xls

`agentFallback` 必须保留，因为 .doc / .xls 仍走该路径（本次不修）。

#### Scenario: 上传 .doc

- **WHEN** fileType === 'word' 且 ext === '.doc'
- **THEN** `parseDocument` 仍调用 `agentFallback`
- **AND** 行为与本次 change 之前完全一致

## Modified Capabilities
- 本 spec 增量更新自 `document-parsers` 已有的 ppt 分支
- 增量变更不影响 docx / xlsx / txt 解析路径
