## 1. 素材库准备

- [x] 1.1 在 `backend/agent-core/src/features/avatar/prompts.ts` 中新增 `GREETING_TEMPLATES` 数组（30 条中文欢迎语模板，使用 `{nickname}` 和 `{avatar}` 占位符），覆盖多种风格（热情、简洁、俏皮等）
- [x] 1.2 移除 `GENERATE_GREETING_SYSTEM_PROMPT`（不再需要）

## 2. Service 重构

- [x] 2.1 修改 `AvatarService.generateGreeting()`：从 `GREETING_TEMPLATES` 随机选取模板，用 `String.replace` 替换 `{nickname}` 和 `{avatar}`，`Promise.resolve` 返回
- [x] 2.2 移除 `greetingCache` 相关代码（`Map` 声明、`get`、`set`、`CACHE_TTL` 常量）
- [x] 2.3 移除 `Promise.race` 超时逻辑和 `responsePromise.then` 异步缓存逻辑
- [x] 2.4 移除 LLM 调用代码（`this.llm.invoke`、`SystemMessage`/`HumanMessage` 构造），`SystemMessage`/`HumanMessage` import 如不再被 `generateAvatar` 之外的代码引用则一并清理

## 3. Controller 清理

- [x] 3.1 检查 `avatar.controller.ts` 中 `generateGreeting` 方法，duration 日志保留（监控优化效果）

## 4. 验证

- [x] 4.1 本地启动服务，新对话应立即可见随机欢迎语（无 LLM 等待延迟）
- [x] 4.2 多次新建对话，验证每次欢迎语不同（随机性生效）
- [x] 4.3 验证昵称为空时显示 `"欢迎回来！"`
- [x] 4.4 验证头像 emoji 正确替换到欢迎语中
- [x] 4.5 验证头像生成（`generateAvatar`）仍正常工作（LLM 调用不受影响）
