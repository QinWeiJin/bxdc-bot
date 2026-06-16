## Why

当前问好（Greeting）通过调用 LLM 生成个性化欢迎语，存在延迟（需要等待 LLM 响应，即使有 3 秒超时兜底），且对 LLM 资源有浪费。改为存量问好素材库随机匹配，建立即返回，无延迟。

## What Changes

- 在 agent-core 的 `AvatarService` 中新增内置问好素材库（约 20-30 条中文欢迎语模板），模板中使用 `{nickname}` 和 `{avatar}` 占位符
- `generateGreeting()` 改为从素材库随机选一条 + 替换占位符，同步返回，**不再调用 LLM**
- 移除 `GENERATE_GREETING_SYSTEM_PROMPT` 和 LLM 相关调用逻辑
- 移除 `greetingCache`（缓存不再需要，素材库本身就是静态的）
- 接口 `POST /features/avatar/greeting` 行为不变（请求/响应格式不变），仅内部实现从 LLM 调用 → 素材库随机匹配
- 前端 `fetchGreeting()` 无需改动

## Capabilities

### New Capabilities
- `greeting-template-library`: 内置问好素材库，包含 20-30 条中文欢迎语模板，随机选取一条并匹配用户昵称/头像，同步立即返回

### Modified Capabilities
- `chat-ui`: 初始欢迎语的具体生成方式从"LLM 个性化生成"改为"素材库随机匹配"，但触发时机和展示行为不变

## Impact

- **agent-core**: `backend/agent-core/src/features/avatar/prompts.ts`（移除 `GENERATE_GREETING_SYSTEM_PROMPT`）、`backend/agent-core/src/features/avatar/service.ts`（重构 `generateGreeting()`，移除 LLM 调用、缓存、超时逻辑）
- **不涉及前端改动**
- **不涉及 gateway 改动**
- **不涉及数据库改动**
