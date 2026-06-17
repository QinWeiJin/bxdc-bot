# Design: Add Python Execution Skill Type

> **配套预设计稿**：[docs/python-execution-skill-design.md](../../../docs/python-execution-skill-design.md)（v1.1，含详细章节、代码改动清单、风险评估、spec 待定项）。本 design.md 是 OpenSpec 视角的浓缩版，重点突出**架构决策与权衡**。
>
> **与 v0.5 通用设计稿的关系**：[docs/script-execution-skill-design.md](../../../docs/script-execution-skill-design.md) 是更早的通用外部服务设计（带 `auth_type` / `request_body_template` / `response_extraction_path`）；本设计**收敛**到 Python 沙箱场景，砍掉 v0.5 的通用化复杂度。

## Context

### 现状

`Skill` 实体支持的 canonical `kind`：`api`（HTTP 代理）、`ssh`（远端命令）、`template`（提示词渲染）、`openclaw`（子规划）。所有类型**全部跑在 Gateway / agent-core 自家逻辑里，不依赖任何外部执行器**。`api` 的出站走 `ApiProxyService.callApi()`；`ssh` 的出站走 `LinuxScriptExecutionService`；`template` 走内存 `{{占位符}}` 替换。

### 业务需求

让 LLM 把 Python 脚本提交到一个**外部沙箱服务**执行，由 Gateway 转发并把执行结果回传给 LLM。沙箱服务本身**不**由本项目实现（脚本如何在隔离环境跑、stdout/stderr 怎么收，由独立服务负责）。

### 关键约束

| 约束 | 来源 |
|---|---|
| **不修改 agent-core**（NestJS） | AGENTS.md §5.5 |
| **不新引入** 任何 Java JAR / npm 包 | JDK 1.8 + Spring Boot 2.7 + Jackson + Vue 3 既有能力已足 |
| **不新引入** 任何 Vue / 前端 npm 包 | 同上 |
| Skill 参数封装与现有保持一致——**外层有 `payload` 包裹** | `java-skills.ts:314-316` `ensureObjectType` 模式 |
| 外部服务的 URL / 请求方法 / **入参 schema** 等**配置在表里** | 用户需求 |
| **Java 端不硬编码** LLM 传什么字段名 | 用户需求（字段名由沙箱 schema 决定） |
| JDK 编译目标 1.8 | AGENTS.md §5.4 |
| 前端 build 零 TS6133 | AGENTS.md §5.6 |

### 利益相关方

- **业务侧**：分析师 / 数据团队需要让 LLM 执行 Python 脚本做数据分析
- **运维侧**：负责在 `python_sandbox` 表配置沙箱服务（admin `890728`）
- **LLM**：作为「脚本执行」工具的调用方（agent-core 透传，不感知 kind）
- **沙箱服务方**：项目外部，独立服务实现

## Goals / Non-Goals

### Goals

1. 新增 `kind: "python"` 的 CONFIG-mode Extension Skill，与 `api`/`ssh`/`template` 并列
2. 外部沙箱接入信息（URL / 方法 / 入参 schema）从 `skills.configuration` 解耦到新表 `python_sandbox`，运维可动态增删
3. Gateway 出站 body = LLM 透传字段整体，**不**在 Gateway 端硬编码字段名；字段名 / 数量 / 类型完全由沙箱侧 `service_params` JSON Schema 决定
4. LLM 入参按 `service_params` 的 `required` + `type` 校验
5. 满足所有约束（零 agent-core 改动、零新依赖、零硬编码、JDK 1.8）

### Non-Goals

- **不**实现「Python 沙箱」本身
- **不**做沙箱侧安全策略（`seccomp` / `cgroup` 等）
- **不**支持脚本上传成文件、版本管理、pip install
- **不**做异步轮询模式
- **不**做模板引擎、JsonPath 提取、auth 注入（首版只内网）
- **不**扩到 Java/Node 等其他沙箱语言
- **不**做 LLM 入参字段映射（如 `script` → `code`）——字段名由沙箱侧 schema 决定

## Decisions

### Decision 1：与 `api`/`ssh`/`template` 并列的新 `kind` 名

**选择**：`kind: "python"`（单词语义，与既有 api/ssh/template 风格一致）

**备选**：
- `kind: "script"`（v0.5 通用设计的命名，可扩到 Java/Node）—— **不采纳**，与本次「只支持 Python」的需求不匹配，未来扩语言时直接复用 `python` + 加 `pythonJava` / `pythonNode` 子 kind 即可

**理由**：单词语义，名字清晰，admin 在 UI 上看到「Python 脚本执行」比「脚本执行」更直白。

### Decision 2：`service_params` 字段语义 = LLM 入参 JSON Schema

**选择**：`service_params TEXT NOT NULL DEFAULT '{}'` 存 **JSON Schema**（`{ type: "object", properties: {...}, required: [...] }`），决定：
1. LLM 调工具时该传什么字段（Zod schema 派生源）
2. Gateway 端按 `required` + `type` 校验入参
3. **不**参与出站 body 拼装（body 直接 = LLM 透传字段）

**关键事实**：
- 字段名 / 数量 / 类型**完全由沙箱侧决定**——沙箱 A 想要 `script+args`，沙箱 B 想要 `code+params`，沙箱 C 想要 `source+stdin+cwd`；admin 配 schema 即可，**零 Gateway 代码改动**
- 存储：`TEXT` 而非 MySQL `JSON`（兼容 5.6 / H2）

**拒绝方案 A**：`service_params` 存 admin 配的固定参数，body = `{...service_params, script, args}` 硬编码合并（v1.0 错误理解）——**误把 LLM 运行时输入当 admin 固定配置**。

**拒绝方案 B**：v0.5 的 Mustache `request_body_template` 模板字符串——**砍掉模板引擎**，body 直接透传，admin 配的 schema 只用于校验。

### Decision 3：Zod schema 派生走现有 `parameterContract` 路径

**选择**：在 `executePythonSkill()` 入口**临时**把 `service_params` 合并到 `effectiveConfig.parameterContract`，复用 `Skill.computeSchemaPropertiesInternal()` 现有遍历逻辑（line 261-279，**对所有 kind kind-agnostic**）→ 写进 `schema_properties` 列 → agent-core 读 `skill.schemaProperties` 直接构 Zod。

**理由**：
- 现有 `computeSchemaPropertiesInternal` 已经对 `parameterContract.properties` 做 kind-agnostic 遍历，**零修改**即可让 python skill 享受同样机制
- 不动 `computeSchemaPropertiesInternal`（路线 B 加 `serviceParams` 字段会动核心逻辑，风险更大）

**关键事实**：`schema_properties` 在 `SkillService.persistSchemaProperties()` 持久化时**一次性计算**写进 DB；执行时不再重算。

### Decision 4：JSON Schema 校验仅支持 `required` + `type`

**选择**：新增 `util/JsonSchemaValidator.java` 工具，~50 行 Jackson 反射实现，**不**引入 JSON Schema 第三方库（如 `everit-org/json-schema` / `networknt/json-schema-validator`）。

**理由**：
- 首版最小集——`required`（必填校验）+ `type`（类型校验）覆盖 90% 实际场景
- 不支持 `enum` / `pattern` / `format` / `minimum` 等高级约束（按需后续扩展）
- 零新增依赖

### Decision 5：LLM 外层 `payload` 包裹复用现有机制

**选择**：python skill 走 `extendedPassthroughSkillToolSchema` = `ensureObjectType(z.object({}).passthrough(), ...)`（[`java-skills.ts:319-321`](file:///d:/IdeaProjects/bxdc-bot/backend/agent-core/src/tools/java-skills.ts#L319-L321)），与 `api`/`ssh`/`template` **完全同一份 schema**。

**关键事实**：
- LLM 看到的工具签名：`z.object({ payload: <schema>.optional() }).passthrough()`
- LLM 实际调用：`{ payload: { script, args, ...按 service_params 决定 } }`
- agent-core 端 `func` 内**自动 unwrap** 一层 `payload`（[`java-skills.ts:1189-1201`](file:///d:/IdeaProjects/bxdc-bot/backend/agent-core/src/tools/java-skills.ts#L1189-L1201)）
- Gateway 端 `executePythonSkill()` 拿到的 `parameters` **已是内部对象，无外层 `payload` 包裹**——**无需** unwrap
- 出站 body = `effectiveParameters` 整体透传

**实施影响**：
- `executePythonSkill()` 实现**不**需要 unwrap 步骤
- 失败语义**不**需要「`payload` 缺失」判定场景
- 与 `api`/`ssh`/`template` 在外层 `payload` 行为上 **100% 一致**——零 kind-specific 分支

### Decision 6：`listExecutionTypes()` 动态从 DB 追加

**选择**：硬编码 3 项（`api` / `ssh` / `template`）+ 动态从 `python_sandbox.enabled=true` 每行映射成 1 个 `type: "python"` 项，label = `<name>: <description>`，复用一份 `buildPythonConfigSchema()`。

**理由**：
- admin 动态加沙箱后前端自动看到，**无需改前端代码**
- 不为每行沙箱创建独立 kind——全部归到 `type: "python"`，靠 `sandboxName` 区分

### Decision 7：写权限与 `server_ledgers` 一致

**选择**：`python_sandbox` 行**只能由 `SKILL_PLATFORM_ADMIN_USER_ID`（`890728`）写入**；普通用户只读 `enabled=true` 行。

**理由**：与 `server_ledgers` 写权限模式一致（AGENTS.md 中已有先例）；沙箱行是高敏资源（URL / schema），admin 管控是安全底线。

## Risks / Trade-offs

| 风险 | 等级 | 缓解 |
|---|---|---|
| **任意 Python 代码执行** | 🔴 高 | 1) 脚本必须经外部沙箱；Gateway 仅做转发<br>2) Gateway 端**不解析、不执行**任何 LLM 输入<br>3) Skill 行 `requires_confirmation=true` 时仍走 LangGraph 确认门（agent-core 既有逻辑，**零增量改动**）<br>4) Gateway 端不暴露脚本正则过滤（误伤率高，留给沙箱侧） |
| **沙箱 URL 不可信** | 🟡 中 | 1) `python_sandbox` 行**只能由 admin 写入**<br>2) 复用 `OutboundUrlNormalizer`（`api` skill 现有 SSRF 防御）<br>3) 沙箱默认放内网 |
| **`service_params` 误配危险字段** | 🟡 中 | `service_params` 是 schema（不是 admin 写的固定参数，不是凭据），但 admin 写错 schema 可能让 LLM 误传不安全字段——Gateway 端按 schema 校验（防超字段 / 类型不匹配），沙箱侧应有独立白名单 |
| **审计绕过** | 🟢 低 | 复用 `ApiProxyService.callApi` → `GatewayHttpClientAuditInterceptor` 自动写 `gateway_outbound_audit_logs` |
| **超大脚本/args 拖垮 LLM 上下文** | 🟡 中 | Gateway 端**不**截断（避免误伤）；`aiHint` 提示「请精简脚本」；沙箱侧控制单次 stdout ≤ 64KB |
| **长任务阻塞 LLM** | 🟡 中 | 出站 HTTP 超时复用 `ApiProxyService` 既有机制；首版固定 sandbox 行不携带 `timeoutSeconds`（沙箱侧自己限速） |
| **沙箱返回非 JSON** | 🟢 低 | `ApiProxyService.parseResponseBody` 已有 HTML / 字符串兜底 |
| **agent-core 升级导致意外行为变化** | 🟢 低 | agent-core 行为对该 kind 是**透明**的 |
| **`kind` 列表膨胀** | 🟡 中 | 当前已有 4 种 + python = 5 种；如未来 ≥ 8 种建议引入 `kindCategory` 二级分类（不在本设计范围） |
| **`kind=python` 与 v0.5 `kind=script` 命名冲突** | 🟢 低 | v0.5 文档是预设计稿、**未落地**；本次走 `python` 是收敛版 |
| **零修改 agent-core 的隐式代价** | 🟢 低 | agent-core 侧 trace 标签统一显示「扩展技能」，不区分 kind；如需「Python 脚本执行」分类标签需额外 PR |
| **存量客户端兼容性** | 🟢 低 | `kind=python` 是新枚举值，老数据 `kind in {api,ssh,template,openclaw}` 行为不变；客户端若 pin 了 kind 枚举需在 `isPythonDraft` / `getConfigSummary` 处做 unknown kind 容错 |

## Migration Plan

**本设计**是**新增能力**，**不**修改任何存量 kind 的行为 → **零迁移**：

1. **DB**：新建 `python_sandbox` 表（6 字段），无存量数据迁移
2. **配置**：`application.properties` **不**新增配置项（沙箱 URL 走 DB，不走 env）
3. **存量 Skill 行**：不受影响（`kind=python` 是新枚举值）
4. **存量 `system_skills` 表**：不受影响

**部署顺序**：
1. 后端部署（含 DDL：Spring `spring.sql.init.mode=always` 自动跑 schema-mysql.sql）
2. 前端部署
3. admin (`890728`) 登录后通过 `/api/python-sandbox` POST 创建首批沙箱行（建议：先建 1 个用于联调）
4. 用户在前端「Extended Skill 管理」新建 `kind=python` 的 Skill，填写 `sandboxName` / `operation` / `interfaceDescription`
5. 联调：让 LLM 调一次该 Skill，验证出站 / 响应 / 审计

**回滚策略**：
- 后端回滚到上个版本 → `python_sandbox` 表无业务引用（仅 admin 创建）→ 业务无感知
- 前端回滚到上个版本 → 用户在 UI 看不到「python」kind 选项（与回滚前一致）
- 紧急回滚可通过 `UPDATE python_sandbox SET enabled = 0 WHERE name = '<sandbox-name>'` 一行命令禁掉（**不**丢数据）

## Open Questions

下游 OpenSpec `proposal` 阶段需拍板的 7 个问题（影响 spec 中 SHALL/SHOULD 措辞）：

1. **`service_params` 派生 schema_properties 的实现路线**：
   - 路线 A：执行时临时合并到 `config.parameterContract`，复用遍历逻辑（**采纳**）
   - 路线 B：新增 `serviceParams` 字段让 `computeSchemaPropertiesInternal` 识别
   - 倾向：**路线 A**（改动小、复用现有协议）

2. **`service_params` JSON Schema 的支持深度**：
   - 首版只支持 `required` + `type` 校验（`JsonSchemaValidator` 工具 ~50 行 Jackson 反射）
   - 不支持 `enum` / `pattern` / `format` / `minimum` 等高级约束
   - 倾向：**首版最小集**；后续按需扩展

3. **是否支持 `service_params` 字段的 `default` 值**：
   - 当 LLM 不传某字段时，是否按 schema 的 `default` 注入？
   - 倾向：**首版不做**；保持「LLM 不传 → 字段缺失」的清晰语义

4. **是否支持超时配置**：
   - 沙箱行首版**不**带 `timeout_seconds` 字段（沙箱侧自己限速）；是否在表里预留该字段以便未来单独配？
   - 倾向：**首版不加**；若运营需要，按需后续 PR 加 `ALTER TABLE`

5. **是否支持 asyncPoll（异步轮询）**：
   - 沙箱默认同步；是否要复用 `asyncPoll` 配置语义做「提交 → 轮询 taskId」？
   - 倾向：**首版不做**；留口子给后续 PR

6. **`sandboxName` 下拉的获取时机**：
   - 表单打开时一次拉取（`onMounted`），还是按需拉？
   - 倾向：表单 `onMounted` 拉一次 + 增删沙箱后下个会话自动刷新

7. **首版 UI 暴露范围**：
   - 前端下拉框只 1 项「Python 脚本执行」还是按沙箱名暴露 N 项？
   - 倾向：**下拉框只 1 项**（`listExecutionTypes` 动态从 `python_sandbox` 每行 enabled=true 映射成 1 个 `type: "python"` 项，label = `<name>: <description>`）—— admin 加新沙箱后前端自动看到，**无需改前端代码**
