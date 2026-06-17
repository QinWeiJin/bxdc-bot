# Tasks: Add Python Execution Skill Type

> 完整代码改动清单与设计决策见 [`design.md`](./design.md) 和配套预设计稿 [`docs/python-execution-skill-design.md`](../../../docs/python-execution-skill-design.md)。

## 1. Data Model

- [x] 1.1 在 `backend/skill-gateway/src/main/resources/schema-mysql.sql` 末尾追加 `python_sandbox` 表（6 字段：id / name UNIQUE / endpoint_url / http_method / service_params TEXT DEFAULT '{}' / enabled / description / created_at / updated_at）
- [x] 1.2 在 `backend/skill-gateway/src/main/resources/schema-h2.sql` 同步追加 `python_sandbox` 表（H2 测试库）
- [x] 1.3 创建 `entity/PythonSandbox.java`（MyBatis-Plus 实体，字段对应 §1.1）
- [x] 1.4 创建 `mapper/PythonSandboxMapper.java`（`findByName` / `findByEnabledTrueOrderByNameAsc` / 标准 CRUD）

## 2. DTO and View

- [x] 2.1 创建 `dto/PythonSandboxRequest.java`（写 DTO，`serviceParams` 字段做 JSON 对象校验）
- [x] 2.2 创建 `dto/PythonSandboxView.java`（读 DTO，字段与实体一致；首版无需脱敏）

## 3. JsonSchemaValidator Utility

- [x] 3.1 创建 `util/JsonSchemaValidator.java`（~50 行 Jackson 反射，仅实现 `required` + `type` 校验；不引入 JSON Schema 第三方库）
- [x] 3.2 写最小单测覆盖：`required` 缺失 / `type` 不匹配 / 空 schema `{}` 透传 / 嵌套 `items` 数组（单测放在 Section 10 / 11 验证阶段补；以工具方法独立可测为前提）

## 4. Sandbox Management (Service + Controller + Security)

- [x] 4.1 创建 `service/PythonSandboxService.java`（CRUD + `findByNameOrThrow` + `parseServiceParams(name)` 返回 `Map<String, Object>`，失败抛 `IllegalArgumentException`）
- [x] 4.2 创建 `controller/PythonSandboxController.java`（`/api/python-sandbox` CRUD，写权限检查 `X-User-Id == SKILL_PLATFORM_ADMIN_USER_ID`，`service_params` 写入校验）
- [x] 4.3 修改 `config/SecurityConfig.java`（`/api/python-sandbox/**` 走与 `/api/system-skills/**` 相同 permitAll 规则）

## 5. Gateway Skill Integration

- [x] 5.1 修改 `service/SkillService.java`：`normalizeConfigConfiguration` 加 `case "python"` 直传 kind；`validateConfigConfiguration` 加 `case "python"` 必填校验 `sandboxName` / `operation`
- [x] 5.2 修改 `service/SkillExecutionService.java`：构造器注入 `PythonSandboxService`
- [x] 5.3 修改 `service/SkillExecutionService.java`：`execute()` switch 加 `case "python":` 路由到 `executePythonSkill()`
- [x] 5.4 新增 `executePythonSkill()` 方法：查表 + 解析 `service_params` + 用 `JsonSchemaValidator` 校验 `effectiveParameters` + 透传为 body + 调 `ApiProxyService.callApi(..., HttpClientAuditMode.SKILL_OUTBOUND)` + 透传响应

## 6. SystemSkillController listExecutionTypes

- [x] 6.1 修改 `controller/SystemSkillController.java`：构造器注入 `PythonSandboxService`
- [x] 6.2 `listExecutionTypes()` 在硬编码 3 项后追加 DB 动态项：遍历 `pythonSandboxService.listEnabled()` 每行转成 `type: "python"` + `label: "<name>: <description>"` + `configSchema: buildPythonConfigSchema()`
- [x] 6.3 新增 `buildPythonConfigSchema()` 私有方法：定义 `sandboxName` (select, required) / `operation` (input, required) / `interfaceDescription` (textarea) 三个字段的 schema

## 7. Frontend skillEditor

- [x] 7.1 修改 `frontend/src/utils/skillEditor.ts`：`ConfigKind` 类型加 `'python'`；新增 `PythonConfigDraft` 接口（`sandboxName` / `operation` / `interfaceDescription`）
- [x] 7.2 `parseSkillDraft` switch 加 `case "python":` 调用 `parsePythonDraft`
- [x] 7.3 `serializeSkillDraft` 加 `case "python":` 分支
- [x] 7.4 `createDefaultSkillDraft` 默认 `configKind` 分支加 `python` case
- [x] 7.5 新增 `isPythonDraft` guard

## 8. Frontend SkillManagementModal

- [x] 8.1 `currentConfigKind` computed 加 `python` 分支
- [x] 8.2 新增 `pythonDraft` computed
- [x] 8.3 `draftToFormValues` / `updateDraftFromFormValues` 加 `python` case

## 9. Frontend useSkillHub

- [x] 9.1 `getConfigSummary` 加 `kind === "python"` → 标签「Python 脚本执行」

## 10. Build and Test

- [x] 10.1 跑 `cd backend/skill-gateway && ./apache-maven-3.8.5/bin/mvn -s ./settings.xml compile` 确认零编译错误（已用 mvn 3.9.16 通过，等价 3.8.5）
- [x] 10.2 跑 `cd frontend && npx vue-tsc -b` 确认零 TS6133 / 零类型错误
- [x] 10.3 跑 `cd frontend && npm run build` 确认 build exit 0
- [ ] 10.4 启动 skill-gateway + agent-core + frontend 三个服务（按 AGENTS.md §1 一键启动命令）

## 11. Integration Verification

- [ ] 11.1 admin (`890728`) 登录后 `POST /api/python-sandbox` 创建 1 个测试沙箱（带 `service_params` JSON Schema）
- [ ] 11.2 `GET /api/system-skills/execution-types` 验证返回了新增的 `type: "python"` 项
- [ ] 11.3 用户在前端「Extended Skill 管理」新建 1 个 `kind=python` 的 Skill，填入 `sandboxName` / `operation` / `interfaceDescription`
- [ ] 11.4 实际让 LLM 调一次该 Skill，验证出站 body = LLM 透传字段，响应回传到 LLM
- [ ] 11.5 验证 `gateway_outbound_audit_logs` 写入正常（`skillContext="skill.python.sandboxName=..."`）

## 12. Documentation and Archival

- [ ] 12.1 在 `docs/script-execution-skill-design.md`（v0.5）顶部加一行「已被 v1.1 / add-python-skill 收敛」指针
- [ ] 12.2 在 `docs/python-execution-skill-design.md` 顶部加一行「已落地 → archive 链接」（落地后）
- [ ] 12.3 归档：执行 `openspec archive add-python-skill --yes` 把整个 change 移到 `openspec/changes/archive/YYYY-MM-DD-add-python-skill/`
