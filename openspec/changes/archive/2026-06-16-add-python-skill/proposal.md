# Add Python Execution Skill Type

## Why

业务侧需要让 LLM 把 Python 脚本提交到一个**外部沙箱服务**执行。当前 `Skill` 实体支持的 canonical `kind` 只有 `api` / `ssh` / `template` / `openclaw`，全部跑在 Gateway / agent-core 自家逻辑里，**不依赖任何外部执行器**。新需求要求在保持现有「Extension Skill 接入、零修改 agent-core、零新增依赖」约束的前提下，让 LLM 通过 Skill 通道把脚本投递到任意第三方沙箱服务（Python 沙箱 / 自建执行器 / 第三方 SaaS），由沙箱负责执行并返回结构化结果。

## What Changes

- 新增 `kind: "python"` 的 Extension Skill 类型，与 `api` / `ssh` / `template` 并列；Gateway 端按 `kind=python` 分发到新的 `executePythonSkill()` 执行逻辑。
- 新建 `python_sandbox` 配置表，存第三方沙箱的 `endpoint_url` / `http_method` / **`service_params` (LLM 入参 JSON Schema)** / `enabled` / `description`；由 admin 在 `/api/python-sandbox` 维护，普通用户只读 `enabled=true` 的沙箱列表。
- Gateway 出站时按 `service_params` 校验 LLM 入参（`required` + `type`），**透传 LLM 字段整体**作为 body 发给沙箱，**不**在 Gateway 端硬编码 `script` / `args` 等字段名；字段名 / 数量 / 类型完全由沙箱侧 schema 决定。
- `GET /api/system-skills/execution-types` 动态从 `python_sandbox.enabled=true` 拉每行转成 `type: "python"` 项，label = `<name>: <description>`，复用一份 `buildPythonConfigSchema()`。
- 新增 `JsonSchemaValidator` 轻量校验工具（仅 `required` + `type`，~50 行 Jackson 反射，**不**引入 JSON Schema 第三方库）。
- 前端 `skillEditor` / `SkillManagementModal` / `useSkillHub` 各加 `python` kind 分支；`ConfigFormRenderer` **不**改（schema 驱动）。
- agent-core (NestJS) **零修改**——`loadGatewayExtendedTools` 已是 kind-agnostic；外层 `payload` 注入 / unwrap 走既有 `ensureObjectType` 通用逻辑。

## Capabilities

### New Capabilities

- `python-execution-skill`: 新增 `kind: "python"` 的 CONFIG-mode Extension Skill，让 LLM 通过 Skill 通道把脚本投递到 `python_sandbox` 表配置的外部沙箱服务，Gateway 端按 `service_params` (JSON Schema) 校验入参并透传字段整体作为出站 body，响应透传整 body；agent-core 零改动。
- `python-sandbox-registry`: 新增 `python_sandbox` 配置表 + `/api/python-sandbox` CRUD 端点，admin (`890728`) 维护沙箱接入信息（URL / HTTP 方法 / `service_params` JSON Schema / 启用开关 / 描述），普通用户只读 `enabled=true` 行供 `sandboxName` 下拉。

### Modified Capabilities

无。现有 `extended-skill-management` / `skill-kind-normalization` / `api-skill-invocation` 等 spec 不限定 kind 枚举，python 加进来属于新增能力（既有 spec 的「extension skill 列表」按 kind-agnostic 设计自动覆盖），**不**修改这些 spec 的 REQUIREMENTS。

## Impact

- **Backend (skill-gateway)**：新增 6 个文件 + 修改 3 个文件
  - 新增：`entity/PythonSandbox.java` / `mapper/PythonSandboxMapper.java` / `dto/PythonSandboxRequest.java` / `dto/PythonSandboxView.java` / `service/PythonSandboxService.java` / `controller/PythonSandboxController.java` / `util/JsonSchemaValidator.java`
  - 修改：`service/SkillService.java`（`normalize/validate` 加 `case "python"`）/ `service/SkillExecutionService.java`（`execute()` switch 加 `case "python":` + 新增 `executePythonSkill()`）/ `controller/SystemSkillController.java`（`listExecutionTypes()` 追加 DB 查询）
  - DDL：`resources/schema-mysql.sql` + `resources/schema-h2.sql` 新建 `python_sandbox` 表
  - `config/SecurityConfig.java` 加 `/api/python-sandbox/**` 路由规则
- **agent-core (NestJS)**：**零修改**
- **Frontend (Vue)**：修改 3 个文件
  - `src/utils/skillEditor.ts`（`ConfigKind` + `PythonConfigDraft` + `parse/serialize` + `isPythonDraft`）
  - `src/components/SkillManagementModal.vue`（`pythonDraft` computed + form 分支）
  - `src/composables/useSkillHub.ts`（`getConfigSummary` 加 `python` → 「Python 脚本执行」）
  - `ConfigFormRenderer.vue` / `SkillHub.vue` **不动**（schema 驱动 / 列表渲染通用）
- **DB**：新建 `python_sandbox` 表（6 字段），无存量数据迁移
- **API**：
  - 新增 `GET/POST/PUT/DELETE /api/python-sandbox[/...]`
  - 修改 `GET /api/system-skills/execution-types`（追加 DB 动态项）
  - 现有 `POST /api/skills/execute` 自动覆盖（按 `kind` 分发）
- **依赖**：0 新增 JAR、0 新增 npm 包（沿用 JDK 1.8 + Spring Boot 2.7 + Jackson + Vue 3 + tdesign 既有能力）
- **审计**：复用 `gateway_outbound_audit_logs`（`skillContext="skill.python.sandboxName=<name>"`）
- **SSRF 防御**：复用 `OutboundUrlNormalizer`（`api` skill 现有）
