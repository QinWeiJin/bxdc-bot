## Context

当前 `AvatarService.generateGreeting()` 通过调用 LLM（`ChatOpenAI`）根据用户昵称和头像生成个性化欢迎语，带 3 秒超时 + 缓存（1h TTL）+ 兜底模板。这引入了不必要的延迟和 LLM 资源消耗。

本次改动将欢迎语生成改为纯客户端侧逻辑：从内置素材库随机选取模板 + 字符串替换，同步立即返回。

## Goals / Non-Goals

**Goals:**
- 欢迎语生成零延迟，建立即返回
- 不消耗 LLM 调用资源
- 前端行为和接口契约不变（`POST /features/avatar/greeting` 请求/响应格式保持兼容）
- 素材库覆盖常见欢迎场景，保持多样性

**Non-Goals:**
- 不修改前端任何代码
- 不修改 gateway
- 不涉及数据库
- 不求与 LLM 版本同等个性化程度（接受模板化话术）
- 不修改 `generateAvatar`（头像生成仍走 LLM）

## Decisions

### 1. 素材库位置：放在 `prompts.ts` 中，与现有 prompt 定义同文件

新文件不必增加，`prompts.ts` 已有 `GENERATE_GREETING_SYSTEM_PROMPT`，改为 `GREETING_TEMPLATES` 数组即可。

### 2. 模板格式：`{nickname}` 和 `{avatar}` 占位符 + 简单字符串替换

不需要模板引擎。示例：
```
"你好，{nickname} {avatar}！今天想聊点什么？"
"欢迎回来，{nickname} {avatar}！有什么可以帮你的？"
```

替代方案考虑：用 `sprintf` 或模板库 → 拒绝，杀鸡用牛刀。

### 3. `generateGreeting()` 改为同步逻辑

由于不再调 LLM，`async` 关键字保留（接口兼容），但内部逻辑变为：
```ts
generateGreeting(nickname: string, avatar: string): Promise<string> {
  const template = GREETING_TEMPLATES[Math.floor(Math.random() * GREETING_TEMPLATES.length)];
  return Promise.resolve(
    template.replace('{nickname}', nickname).replace('{avatar}', avatar)
  );
}
```

### 4. 移除 LLM 超时兜底和缓存

- `greetingCache` 移除（素材库无状态，无需缓存）
- `Promise.race` 超时逻辑移除
- `defaultGreeting` 兜底改为极简写死：当 `nickname` 为空时返回 `"欢迎回来！"`（已在 controller 中处理）

### 5. AvatarService 构造函数保留

虽然 `generateGreeting` 不再使用 `this.llm`，但同一个 `AvatarService` 仍提供 `generateAvatar` 需要 LLM，因此构造函数不必改动。

### 6. Controller 改动最小化

`avatar.controller.ts` 中 `generateGreeting` 方法行为不变，仅保留日志记录（duration 将极短）。

## Risks / Trade-offs

- **[Risk] 个性化程度下降**：LLM 生成能针对用户名做语义匹配（如 "小明" → "阳光"），模板是固定的 → **Mitigation**：增加模板数量（~30 条）覆盖不同风格和场景，随机选取保证每次体验不同。
- **[Risk] 同一用户多次刷新/重建对话看到的欢迎语可能重复**：随机可能抽到相同模板 → **Mitigation**：模板数量够多（30 条），重复概率低，且欢迎语仅新对话展示一次，影响极小。
