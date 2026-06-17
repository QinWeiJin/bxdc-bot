# Capability: (no new capability)

> **Purpose**: 此 change 是 bug fix，**不引入新 capability**。修 7 处预存在编译错误：
> - 4 处 Java：`BxdcbotRunCompletionController.java` 的 3 处 `Map.of()` + `SkillExecutionService.java` 缺 `conversationId` 字段
> - 3 处 TS：`java-skills.ts` 的 `gatewaySkillMutationHeaders` 函数签名 / 函数体 / 调用点不匹配

## ADDED Requirements

（无 — 这是 bug fix，不新增用户可见行为）

### Requirement: 7 处编译错误全部修复

`mvn -s ./settings.xml compile` 在 `backend/skill-gateway/` 静默通过 + `cd backend/agent-core && npm run build` 静默通过。

#### Scenario: skill-gateway 编译通过
- **WHEN** `cd backend/skill-gateway && mvn -s ./settings.xml compile`
- **THEN** 输出 `BUILD SUCCESS`，无 `Map.of()` 找不到符号错误
- **AND** `SkillExecutionService` 的 `request.conversationId` 编译通过

#### Scenario: agent-core 编译通过
- **WHEN** `cd backend/agent-core && npm run build`
- **THEN** `java-skills.ts` 0 编译错误
- **AND** `gatewaySkillMutationHeaders` 函数签名 = 4 个参数（apiToken, userId?, sessionId?, conversationId?）
