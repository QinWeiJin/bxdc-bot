# 新 Tool 开发接入指南

## 一、现有架构速览

整个系统分三层，新 tool 需要在这三层各加一点东西：

```
前端 (Vue 3)                     Gateway (Java/Spring Boot)            Agent-Core (NestJS/LangGraph)
────────────                     ──────────────────────────            ─────────────────────────────
SkillManagementModal.vue  ──→   SkillController (CRUD)          ──→   loadGatewayExtendedTools()
    │ 用户填表单                       │ 存 skills 表                       │ 从 GET /api/skills 拉取
    │ 选 kind=api/ssh/template        │ configuration 列 = JSON         │ 注册为 LangChain DynamicStructuredTool
    ▼                                 ▼                                   ▼
ConfigFormRenderer.vue          SkillExecutionService.execute()    LLM 调用 tool → POST /api/skills/execute
    │ 按 configSchema 渲染表单        │ 按 kind 分发：                       │
    │ serializeSkillDraft() → JSON   │  "api" → ApiProxyService            │ { skillId, parameters }
    ▼                                 │  "ssh" → SSHExecutorService
    ▼                                 │  "template" → 模板渲染
```

### 现有 kind 一览

| kind | 用途 | 表单字段 | 执行者 |
|------|------|---------|--------|
| `api` | HTTP 代理调用 | method, endpoint, headers, body, asyncPoll... | `ApiProxyService` |
| `ssh` | SSH 远程执行 | command, server_lookup | `SSHExecutorService` |
| `template` | 提示词模板渲染 | prompt | 直接在 Gateway 渲染 |
| `openclaw` | LLM 子规划 | systemPrompt, allowedTools | agent-core 侧执行 |

---

## 二、新增一种 Tool 的步骤（以"文件操作 FileOperation"为例）

### 第 1 步：在 Gateway 端新建执行 Service

在 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/` 下新建类：

```java
// FileOperationService.java
package com.lobsterai.skillgateway.service;

import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service
public class FileOperationService {

    /**
     * 执行文件操作。config 来自 skill 的 configuration JSON，
     * parameters 是 LLM 传过来的入参。
     */
    public Object execute(Map<String, Object> config, Map<String, Object> parameters) throws IOException {
        String basePath = (String) config.getOrDefault("basePath", "/tmp");
        String operation = (String) parameters.getOrDefault("operation", "read");
        String filePath = (String) parameters.get("filePath");

        Path resolved = Paths.get(basePath, filePath).normalize();
        if (!resolved.startsWith(Paths.get(basePath).normalize())) {
            throw new SecurityException("Path traversal blocked: " + filePath);
        }

        switch (operation) {
            case "read":
                return Collections.singletonMap("content",
                    new String(Files.readAllBytes(resolved)));
            case "write":
                String content = (String) parameters.get("content");
                Files.write(resolved, content.getBytes());
                return Collections.singletonMap("status", "ok");
            case "list":
                // 列出目录...
                break;
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
        return Collections.emptyMap();
    }
}
```

**关键约定**：
- 入参签名为 `(Map<String, Object> config, Map<String, Object> parameters)` 或类似
- 返回值可以是 `Map`、`String`、`Object`，会被 Jackson 序列化为 JSON 返回给 agent-core

### 第 2 步：在 SkillExecutionService 里加分发

修改 `SkillExecutionService.java` 的 `execute()` 方法，在 switch 里加新 case：

```java
// 注入新 Service
private final FileOperationService fileOperationService;

// 在 execute() 方法的 switch 里添加：
case "file_operation":
    return executeFileOperationSkill(config, effectiveParameters);

// 实现执行方法：
private Object executeFileOperationSkill(Map<String, Object> config, Object parameters) throws Exception {
    return fileOperationService.execute(config, asMap(parameters));
}
```

### 第 3 步：在前端 registry 里注册新 kind

修改 `frontend/src/utils/skillEditor.ts`：

```typescript
// 1. 扩展 ConfigKind 类型
export type ConfigKind = 'api' | 'ssh' | 'template' | 'file_operation' | 'database' | 'browser'

// 2. 定义新 draft 接口
export interface FileOperationConfigDraft {
  kind: 'file_operation'
  operation: string   // read / write / list / delete
  basePath: string    // 允许操作的根目录
  // ... 其他字段
}

// 3. 扩展 SkillConfigDraft union
export type SkillConfigDraft = ApiConfigDraft | SshConfigDraft | TemplateConfigDraft 
                              | OpenClawConfigDraft | FileOperationConfigDraft | ...

// 4. 加 type guard
export function isFileOperationDraft(draft: SkillConfigDraft | null): draft is FileOperationConfigDraft {
  return draft?.kind === 'file_operation'
}

// 5. 在 parseSkillDraft 里加 case:
case 'file_operation':
  return { draft: parseFileOperationDraft(parsed), error: null }

// 6. 在 serializeSkillDraft 里加序列化分支
// 7. 在 createDefaultSkillDraft 里加默认值
```

### 第 4 步：注册表单 Schema（方式一：Gateway API 返回）

在 `SystemSkillController.java` 的 `listExecutionTypes()` 里添加：

```java
// File Operation Skill
Map<String, Object> fileType = new LinkedHashMap<>();
fileType.put("type", "file_operation");
fileType.put("label", "文件操作");
fileType.put("configSchema", buildFileOperationConfigSchema());
types.add(fileType);
```

然后实现 `buildFileOperationConfigSchema()`，定义各字段的 `type`、`label`、`ui`（input/select/textarea/jsonEditor/number/checkbox）、`required`、`default`、`aiHint` 等。

前端 `SkillManagementModal.vue` 通过 `ConfigFormRenderer` 组件会根据这个 schema 自动渲染表单，无需改 Vue 模板。

### 第 5 步（可选）：如果是 Built-in Tool 而非扩展 Skill

如果新 tool 不需要用户自己创建实例（像 `compute`、`server_lookup` 一样是内置的），则在 agent-core 侧直接写一个 LangChain Tool：

在 `backend/agent-core/src/tools/java-skills.ts` 里仿照 `JavaComputeTool`：

```typescript
import { z } from "zod";

const fileOperationSchema = z.object({
  operation: z.enum(["read", "write", "list"]).describe("操作类型"),
  filePath: z.string().describe("文件路径"),
  content: z.string().optional().describe("写入内容（write 时必填）"),
});

export class FileOperationTool extends DynamicStructuredTool<typeof fileOperationSchema> {
  constructor(gatewayUrl: string, apiToken: string) {
    super({
      name: "file_operation",
      description: "在服务器上执行文件操作（读/写/列目录）。...",
      schema: fileOperationSchema,
      func: async (args) => {
        const response = await axios.post(`${gatewayUrl}/api/system-skills/execute`, {
          toolName: "file_operation",
          arguments: args,
        }, {
          headers: { "X-Agent-Token": apiToken, "Content-Type": "application/json" },
        });
        return JSON.stringify(response.data);
      },
    });
  }
}
```

然后在 `backend/agent-core/src/agent/agent.ts` 的 `baseTools` 数组里注册：

```typescript
const baseTools: BindableAgentTool[] = [
  // ... 现有工具
  new FileOperationTool(gatewayUrl, apiToken),
];
```

---

## 三、两种接入模式对比

| 维度 | 扩展 Skill（Extension） | 内置 Tool（Built-in） |
|------|------------------------|---------------------|
| 用户能否自己创建 | 是，前端表单创建并持久化到 `skills` 表 | 否，代码写死 |
| 配置存储 | `skills.configuration` JSON 列 | 无（或 `system_skills` 表） |
| 执行入口 | `POST /api/skills/execute` | `POST /api/system-skills/execute` 或专用端点 |
| 注册位置 | Gateway 启动时 agent-core 自动拉取 | agent.ts 手动注册 |
| LLM 看到的工具名 | `extended_<拼音化名称>` | 固定的英文名如 `compute` |
| 适用场景 | 用户自定义的 API/SSH/DB 连接 | 平台通用能力（计算、文件、浏览器） |

---

## 四、新增 Tool 的 Checklist

无论选哪种模式，确保以下各层都改到了：

```
□ Gateway - 新建 XxxService.java（执行逻辑）
□ Gateway - SkillExecutionService.execute() 加 switch case
□ Gateway - SystemSkillController.listExecutionTypes() 加 configSchema
□ Gateway - 如有新依赖（如 Selenium、JDBC），在 pom.xml 加 dependency
□ 前端  - skillEditor.ts: 扩展 ConfigKind / 加 draft 接口 / 加解析/序列化逻辑
□ 前端  - SkillManagementModal.vue: 在 configKindOptions 里加新选项
□ agent-core - 如果是 Built-in: java-skills.ts 加新 Tool class，agent.ts 注册
□ agent-core - 如果是 Extension: 无需改动（自动从 Gateway 拉取）
```

---

## 五、关键设计原则

1. **所有 Skill 统一走 `POST /api/skills/execute`**（Extension 模式），Gateway 端按 `configuration.kind` 分发。不要为每种类型建新端点。
2. **参数透传**：agent-core 不解析 skill 的业务参数，Zod schema 只是给 LLM 看的约束。实际参数原样传给 Gateway。
3. **安全过滤**：SSH 命令有 `SecurityFilterService.isCommandSafe()`，文件操作要有路径穿越检查，数据库操作要有 SQL 注入防护。
4. **审计**：通过 `GatewayOutboundAuditService` 记录所有外部调用。
