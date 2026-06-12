## 1. 验证 mem0 `/deletemem` 端点契约

- [ ] 1.1 联调运维确认 mem0 服务已部署 `POST /deletemem` 接口，curl 实测 `{ userid: "test-user-001", sentencein: "", sentenceout: "" }` 返回 `{ code: 200, message: "...", userid: "test-user-001", time: ... }`
- [ ] 1.2 验证「不存在的 userid」幂等返回 200 而非报错
- [ ] 1.3 验证「跨用户隔离」：`POST /deletemem` 指定 user A，不影响 user B 的记忆

## 2. agent-core: MemoryService 新增方法

- [ ] 2.1 在 `backend/agent-core/src/mem/memory.service.ts` 新增 `async deleteAllMemories(userId: string): Promise<void>`，POST 到 `${mem0Url}/deletemem` 携带 `{ userid, sentencein: '', sentenceout: '' }`（**参数与 `/madd` 对齐**，按 spec mem0-integration 契约），按现有 `msearch`/`madd` 错误处理风格 catch + logger
- [ ] 2.2 在同一文件复用 `readMem0EnabledFlag()`，暴露 `async getMemoryStatus(userId: string): Promise<{ enabled: boolean }>`（**只**返回 enabled，**不**返回 hasMemory）
- [ ] 2.3 删除/状态接口在 `MEM0_ENABLED=false` 时 MUST NOT 真正请求 mem0，直接返回 `{ enabled: false }`（沿用现有 `readMem0EnabledFlag` 语义）
- [ ] 2.4 `getMemoryStatus` 内部 try/catch 包 mem0 不可达情况，**不**抛错，返回 `{ enabled: false }`（前端据此不渲染入口）

## 3. agent-core: MemoryController 暴露新端点 + 修 add 守卫

- [ ] 3.1 在 `backend/agent-core/src/controller/memory.controller.ts` 增加 `POST /memory/delete` 端点，调用 `memoryService.deleteAllMemories(userId)`，按现有 `addMemory` 端点同样控制台日志
- [ ] 3.2 在同一 controller 增加 `GET /memory/status?userId=xxx` 端点，返回 `getMemoryStatus` 结果（**只**含 enabled）
- [ ] 3.3 增加跨用户守卫：`POST /memory/delete` 和 `POST /memory/add` MUST 校验请求体 `userId` 与当前 session 用户一致（不一致返回 403），调 `useUser` 或注入 `Request` 拿 session（沿用项目现有鉴权模式）
  - **本次 add 端点守卫补强**（修复已知安全 bug，不影响前端当前调用）
- [ ] 3.4 重启 agent-core，`curl POST http://localhost:3000/memory/delete -d '{"userId":"u1"}'` 自测返回 200

## 4. frontend: memory 客户端封装

- [ ] 4.1 在 `frontend/src/composables/` 新建 `useMemory.ts`，导出 `getMemoryStatus(userId): Promise<{ enabled: boolean }>`（**只**有 enabled）、`deleteUserMemory(userId): Promise<void>`、`addUserMemory(userId, text): Promise<void>`，基础 URL 沿用现有 `useApiBase()` 或 `import.meta.env.VITE_API_BASE` 模式
- [ ] 4.2 三个方法统一错误处理：把 HTTP 非 2xx 与 `code !== 200` 都 throw 一个带原始消息的 Error（前端 toast 友好显示）

## 5. frontend: 新增 MemoryInitModal 组件

- [ ] 5.1 在 `frontend/src/components/` 新建 `MemoryInitModal.vue`，用 TDesign `<t-dialog>` 渲染初始化表单，包含两个 `<t-textarea>`：「我是谁」（必填，maxlength=500）和「我的兴趣 / 性格特征」（可选，maxlength=500）
- [ ] 5.2 暴露 props：`visible` (v-model)、`userId`、`loading` (受控)
- [ ] 5.3 暴露 emit：`submit` (payload: `{ who: string, hobbies: string }`)、`cancel`、`later`
- [ ] 5.4 提交时校验「我是谁」非空，错误显示在字段下方
- [ ] 5.5 footer 三按钮：取消 / 稍后再说 / 保存初始化（主题 primary，loading 期间禁用全部）

## 6. frontend: ProfileEditModal 集成入口

- [ ] 6.1 修改 `frontend/src/components/ProfileEditModal.vue`，在 `.emoji-section` 之后、footer 之前插入 `MemoryManagementSection` 子区块（先内联在该组件里，不抽组件，后续可拆）
- [ ] 6.2 该区块内放一个 `useMemory` 调 `getMemoryStatus` 的 onMounted 钩子；响应 `enabled === false` 时**不渲染**（用 `v-if`），否则渲染一个 `<t-link theme="danger" hover underline>` 的「清除记忆」文字按钮
- [ ] 6.3 点击「清除记忆」打开危险确认弹窗（同样用 `<t-dialog>`, theme=danger, header 写「清除记忆（不可恢复）」），提供「取消」和「确认清除」两个按钮；`Enter`/`Esc`/遮罩点击**不**触发清除（用 `:close-on-enter` / `:close-on-esc-keydown="false"` 配合 `:close-on-overlay-click="false"`）
- [ ] 6.4 用户点「确认清除」后调 `deleteUserMemory(currentUser.id)`：
  - 成功 → 关闭危险弹窗 + 打开 MemoryInitModal
  - 失败 → 弹 toast「清空失败：{err.message}」，危险弹窗保持

## 7. frontend: 初始化流程串接

- [ ] 7.1 危险确认后弹出 MemoryInitModal 接收 `{ who, hobbies }` payload
- [ ] 7.2 按 spec 拼接：`text = hobbies.trim() ? \`${who}。${hobbies}\` : who`
- [ ] 7.3 调 `addUserMemory(currentUser.id, text)`：
  - 成功 → toast 成功「记忆初始化更新成功」+ 关闭 MemoryInitModal + 关闭 ProfileEditModal
  - 失败 → toast 警告「记忆已清空但初始化失败：{err.message}，请重试」+ MemoryInitModal 保持打开供重试
- [ ] 7.4 用户点「稍后再说」：关闭 MemoryInitModal + 关闭 ProfileEditModal（不恢复已清空的记忆）

## 8. 配置开关端到端验证

- [ ] 8.1 改 `agent-core/.env` 设 `MEM0_ENABLED=false`，重启 agent-core；前端打开资料编辑弹窗 — 「清除记忆」入口**完全不渲染**
- [ ] 8.2 改回 `MEM0_ENABLED=true`，重启 agent-core；前端打开资料编辑 — 入口出现，可点可走全流程
- [ ] 8.3 验证：手工 grep 前端 `dist/` 产物里没有 `deleteUserMemory` 的 build-time 路径（必须每次都从 `/memory/status` 动态判断，build-time 跳过开关会破坏规约）

## 9. 跨用户隔离手工测试

- [ ] 9.1 用户 A 走完「清除 + 初始化」全流程，记录 A 初始化文本
- [ ] 9.2 用户 B 在另一浏览器/无痕模式登录，curl 模拟 `POST /memory/delete` 携带 A 的 userId — 期望返回 403
- [ ] 9.3 用户 B 模拟 `POST /memory/add` 携带 A 的 userId — 期望返回 403
- [ ] 9.4 验证 A 记忆仍为 A 初始化文本，未被 B 污染

## 10. 文档与归档

- [ ] 10.1 更新 `AGENTS.md`（如果新增任何规约，例如 `MEM0_ENABLED` 已被本流程使用）
- [ ] 10.2 用 `openspec archive add-memory-init-and-clear` 归档本次 change，归档后 `openspec/specs/memory-initialization-flow/spec.md` 与 `openspec/specs/mem0-integration/spec.md`/`openspec/specs/user-profile/spec.md` 落地下一次主 spec（自动 sync 机制会跑）
