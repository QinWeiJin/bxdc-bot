## Why

`mvn -s ./settings.xml spring-boot:run` 和 `npm run start:dev` 当前都编译失败：
- **backend (mvn)**：4 个错误 — `Map.of()`（Java 9+）3 处 + `SkillExecutionService.ExecuteRequest` 缺 `conversationId` 字段 1 处
- **agent-core (tsc)**：3 个错误 — `gatewaySkillMutationHeaders` 函数签名缺第 4 个 `conversationId` 参数，调用点传 4 个参数 / 函数体用 2 处

这些错误阻止了 3 个服务启动，**当前 0 服务在跑**。属于项目预存在的技术债（违反 AGENTS.md 5.4 的 JDK 1.8 约束），不是新引入的。

## What Changes

- **`BxdcbotRunCompletionController.java`**：3 处 `Map.of(k, v)` → `Collections.singletonMap(k, v)`（JDK 1.2+ 兼容）
- **`SkillExecutionService.java`**：给 `ExecuteRequest` 内部类加 `public String conversationId;` 字段
- **`java-skills.ts`**：给 `gatewaySkillMutationHeaders` 加第 4 个可选参数 `conversationId?: string`，函数体加 `if (conversationId)` 块设 `X-Conversation-Id` header。调用点（line 1199）已经是传 4 个参数，无需改

**无 BREAKING 变更**。修复后 `mvn spring-boot:run` 和 `npm run start:dev` 都能编译通过，3 个服务可启动。

## Capabilities

### New Capabilities
- （无 — 这是 bug fix，不新增 capability）

### Modified Capabilities
- （无 — `gatewaySkillMutationHeaders` 是工具函数不在 spec 覆盖范围内；`ExecuteRequest` 是内部数据传输对象，不在 spec 覆盖范围内）

## Impact

- **后端**：
  - `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/BxdcbotRunCompletionController.java`（3 行改）
  - `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/SkillExecutionService.java`（1 字段加）
- **agent-core**：
  - `backend/agent-core/src/tools/java-skills.ts`（1 函数签名改 + 1 块加）
- **测试**：
  - 编译通过后跑 `mvn -s settings.xml test` 验证 skill-gateway 单测不回归
  - `cd backend/agent-core && npm run build` 验证 agent-core 不回归
- **回归风险**：
  - `Map.of` → `Collections.singletonMap` 在 2-entry 场景语义一致（不可变 + 1 元素）
  - 加 `conversationId` 字段是 nullable，默认 null，不影响现有调用方
  - `gatewaySkillMutationHeaders` 加可选参数，调用方不传时行为不变
