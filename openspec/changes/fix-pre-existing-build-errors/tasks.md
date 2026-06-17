## 1. 后端：3 处 `Map.of` → `Collections.singletonMap`

- [ ] 1.1 `BxdcbotRunCompletionController.java:67` — `Map.of("error", "invalid_internal_token")` → `Collections.singletonMap("error", "invalid_internal_token")`
- [ ] 1.2 `BxdcbotRunCompletionController.java:117` — `Map.of("error", "runId is required")` → `Collections.singletonMap("error", "runId is required")`
- [ ] 1.3 `BxdcbotRunCompletionController.java:120` — `Map.of("error", "conversationId is required")` → `Collections.singletonMap("error", "conversationId is required")`
- [ ] 1.4 确认 `import java.util.Collections;` 已存在（如缺则补）

## 2. 后端：ExecuteRequest 加 conversationId 字段

- [ ] 2.1 `SkillExecutionService.java:728` `public static class ExecuteRequest` 内部，加 `public String conversationId;` 字段（紧跟 `sessionId` 后）

## 3. agent-core：gatewaySkillMutationHeaders 补第 4 参数

- [ ] 3.1 `java-skills.ts:996` 函数签名改为 `export function gatewaySkillMutationHeaders(apiToken: string, userId?: string, sessionId?: string, conversationId?: string): Record<string, string>`
- [ ] 3.2 `java-skills.ts:1008` 函数体加 `if (conversationId && String(conversationId).trim()) { headers["X-Conversation-Id"] = String(conversationId).trim(); }`（与现有 sessionId 块并列）
- [ ] 3.3 调用点 `java-skills.ts:1199` 不变（已传 4 个参数）

## 4. 验证

- [ ] 4.1 `cd backend/skill-gateway && mvn -s ./settings.xml compile` 静默通过
- [ ] 4.2 `cd backend/agent-core && npm run build` 静默通过
- [ ] 4.3 启动 3 个服务，验证端口 18080/3000/5173 都 LISTENING

## 5. 归档

- [ ] 5.1 `openspec archive fix-pre-existing-build-errors --yes`
- [ ] 5.2 `git status` 确认无 dist/.m2/node_modules 污染
