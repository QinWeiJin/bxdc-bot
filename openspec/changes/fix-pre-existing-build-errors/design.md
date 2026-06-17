## Context

项目当前 0 服务在跑（3 服务全停）。根因是源码里 7 处预存在编译错误：
- 4 处 Java 错误（违反 AGENTS.md 5.4 的 JDK 1.8 约束 + 1 处缺字段）
- 3 处 TS 错误（函数签名 / 调用点不一致）

修复目标是让 `mvn spring-boot:run` 和 `npm run start:dev` 都能编译通过，3 服务可启动。

## Goals / Non-Goals

**Goals:**
- 修复 7 处编译错误，0 行为变更
- 修复后 `mvn compile` 和 `tsc --noEmit` 静默通过
- 修复不引入新依赖、不改 schema、不改 API 行为

**Non-Goals:**
- 不重构 `BxdcbotRunCompletionController` 的整体结构
- 不优化 `SkillExecutionService` 的字段命名 / 类型
- 不修复 AGENTS.md 里其他没列出的技术债
- 不修 `Map.of` 之外的 Java 9+ 语法（其他文件如有，自行 follow-up）

## Decisions

### Decision 1: `Map.of(k, v)` → `Collections.singletonMap(k, v)`

`BxdcbotRunCompletionController` 3 处 `Map.of("error", "...")` 都是 2-entry 不可变 map。`Collections.singletonMap(k, v)`（JDK 1.2+）在 2-entry 场景语义完全等价（不可变 + 1 元素 / 不允许 null 值 / 返回 `Map<K,V>`），无需引入 `HashMap + put + Collections.unmodifiableMap` 的冗长模板。

**Alternatives considered:**
- `new HashMap<>(){{put("k","v");}}` — 可变，不符合原意
- `Collections.unmodifiableMap(new HashMap<>(){{put...}})` — 冗长，对 2-entry 过度
- 升级到 JDK 11+ — 违反 AGENTS.md 5.4（"5.4 Java 版本与 language level 必须保持 JDK 1.8"），需要团队评审

### Decision 2: `ExecuteRequest` 加 `public String conversationId;`

`SkillExecutionService.java:153` 调 `request.conversationId` 但字段不存在。看上下文这是个内嵌 static class 的 public 字段（其他字段都是 `public String xxx;`），所以**直接加 public 字段**保持风格一致。

**Alternatives considered:**
- 加 getter/setter — 现有字段都是裸 public field，加 getter 会破坏风格
- 改名 `request.getConversationId()` — 现有字段没有 getter（如 `getSessionId()` 是有的但选择性加的）— 不一致

### Decision 3: `gatewaySkillMutationHeaders` 加 4th 可选参数

`java-skills.ts:1199` 调用时已传 4 个参数（`apiToken, userId, executeSessionId, options?.conversationId`），但函数定义只有 3 个参数。这说明调用点**已经按 4 参设计**写好了，函数定义是漏改了第 4 参。

所以修复 = 函数签名补 4 参 + 函数体加 `if (conversationId)` 块设 `X-Conversation-Id` header。调用点不动。

## Risks / Trade-offs

- **[Risk] `Collections.singletonMap` 不允许 null key/value** — 当前所有调用方传的 `"error"` 和 message 都是非 null 字面量，0 风险
- **[Risk] `ExecuteRequest.conversationId` 字段加在哪里** — 加在 `sessionId` 之后（line 735 后），保持字段顺序按代码风格无关紧要，加在任何位置都不影响 Java 编译
- **[Risk] 修完一处暴露另一处编译错误** — 已知项目有"Map.of 违例"的预存技术债，可能其他文件还有。本次 change 只修 `mvn compile` 报出来的 4 处，其他文件如有 follow-up
- **[Trade-off] 不升级到 JDK 11+** — 接受 AGENTS.md 5.4 的约束，不为这一处 API 升级

## Migration Plan

无需 migration、无需 schema 变更、无需新配置。

部署：
1. 重启 skill-gateway（自动重编译）
2. 重启 agent-core（`--watch` 已重编译，0 操作）
3. 重启 frontend（无需，HMR 已生效）

回滚：`git revert` 单 commit 即可。
