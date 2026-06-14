## Context

`mem0` 服务（[mem0-integration spec](file:///Users/dccb/botproject/bxdc-bot/openspec/specs/mem0-integration/spec.md)）目前提供 `/msearch` 和 `/madd` 两个端点，由 `agent-core` 的 [MemoryService](file:///Users/dccb/botproject/bxdc-bot/backend/agent-core/src/mem/memory.service.ts) 包装。前端只能间接触发（通过对话）。`MemoryController`（[memory.controller.ts](file:///Users/dccb/botproject/bxdc-bot/backend/agent-core/src/controller/memory.controller.ts)）对外暴露 `/memory/add` 供调试/批量场景手动注入，但没有**删除**和**状态查询**端点。

资料编辑页 [ProfileEditModal.vue](file:///Users/dccb/botproject/bxdc-bot/frontend/src/components/ProfileEditModal.vue) 当前是「昵称 + emoji 头像选择器」的组合，emoji 网格下方是空白。用户希望在此位置加一个**不显眼但可发现**的「清除记忆」入口，触发「清空 → 重新初始化」两步流程。

`MEM0_ENABLED`（[.env](file:///Users/dccb/botproject/bxdc-bot/backend/agent-core/.env#L20)）作为总开关已经存在（默认 `true`），本次**只读不增**新环境变量。

## Goals / Non-Goals

**Goals:**
- 给用户提供一个**安全**的两步流程：清空当前全部 mem0 记忆 → 重新写入用户自填的「我是谁 / 我的兴趣」种子
- 入口视觉上**足够低调**（避免误操作），但语义可发现（明确写出「清除记忆」字样）
- 危险操作**二次确认**（红色警告 + 必须点「确认清除」按钮）
- 全部受 `MEM0_ENABLED` 配置门控
- 新增 `mem0` `/deletemem` 端点的契约（spec），但 mem0 服务本身不属本仓库范围
- 不动 agent-core 的 LLM 调度层（AGENTS.md 5.5 规约），仅在 MemoryService 旁路加删除方法

**Non-Goals:**
- 不做单条记忆的细粒度增删改（只支持「全量清空」和「批量写入」两把大刀）
- 不做记忆版本/快照/回滚
- 不做管理员视角的多用户记忆管理（仅当前登录用户本人）
- 不修改 `MEM0_URL` 指向（沿用现有 `http://39.104.81.41:8001`）
- 不修改 `MEM0_ENABLED` 的解析逻辑（沿用 MemoryService 现成的 `readMem0EnabledFlag`）

## Decisions

### Decision 1: 新接口放在 agent-core，不放 gateway

- **选择**：在 [memory.controller.ts](file:///Users/dccb/botproject/bxdc-bot/backend/agent-core/src/controller/memory.controller.ts) 增 `POST /memory/delete` 和 `GET /memory/status`，与现有 `POST /memory/add` 同一前缀。
- **为什么**：mem0 调用本来就在 agent-core 的 `MemoryService` 里（gateway 没有 mem0 客户端），保持调用方内聚。前端统一打到 agent-core 的 `:3000` 端口（现有 `MEMORY_BASE_URL` 配置）。
- **替代方案**：在 gateway 加 `MemoryController` 做透传 — 增加一次 HTTP 跳转 + 重复 token 校验，无收益。

### Decision 2: 入口放在 ProfileEditModal 内部、emoji 网格下方

- **选择**：在 [ProfileEditModal.vue](file:///Users/dccb/botproject/bxdc-bot/frontend/src/components/ProfileEditModal.vue) 的 `.emoji-section` 之后插入一个 `<MemoryManagementSection/>` 子组件。
- **为什么**：用户明确指定「头像选择下面」。不抽到 ProfileEditModal 之外（避免在 settings 页等其他位置同时出现两个入口，导致误操作面扩大）。
- **替代方案**：放在 SettingsView 的「数据」分类 — 离「用户身份」上下文太远，违反「资料编辑一起管」的产品逻辑。

### Decision 3: 「清除记忆」按钮视觉低饱和、不放主色

- **选择**：用 TDesign 的 `<t-link theme="danger" underline="hover">清除记忆</t-link>` 或带 `var(--td-text-color-secondary)` 的文字按钮，**没有边框、没有主色背景**，hover 时才出现 danger 色。
- **为什么**：用户明确要求「清除按钮足够隐蔽」。danger-link 兼顾「不被一眼点到」和「点了之后有危险感」两个目标。
- **替代方案**：图标按钮（垃圾桶 🗑️） — 容易跟「删除对话」「删除消息」混淆；用户的心智模型里 emoji=头像 / 垃圾桶=消息清理。

### Decision 4: 两步流程串行、不并发

- **选择**：先 `POST /memory/delete` 等待返回 → 弹初始化表单 → 用户提交 → `POST /memory/add`。
- **为什么**：用户语义就是「先清后写」。并发调会留时间窗口，旧的记忆可能在初始化写入之前被 agent 检索到。
- **替代方案**：并发 — 失败时记忆状态不确定，不接受。

### Decision 5: 删除用 mem0 `/deletemem` 端点，参数与 `/madd` 对齐

- **选择**：POST `${MEM0_URL}/deletemem`，request body `{ sentencein?: string, sentenceout?: string, userid: string }`（**userid 必填**，sentencein / sentenceout 可省略/空字符串）。
- **成功响应**：`{ code: 200, message: '提示信息', userid: xxx, time: xxx }`
- **失败响应**：`{ code: 400, message: '用户记忆删除失败' }`
- **为什么**：用户明确「/deletemem 的参数结构跟 add 一样」（最初说的是 /msearch，但修正为跟 `/madd` 一致）。`/madd` 是 `{ sentencein, sentenceout, userid }`，新端点复用同样字段，sentencein / sentenceout 留空等价于"全量删除"。
- **替代方案**：用 mem0 高级 filter `filter: { user_id: userId }` — 需要 mem0 服务升级，不在本仓库控制范围。

### Decision 6: 初始化记忆合并为单条写入，拼接在 frontend 完成

- **选择**：frontend 把「我是谁」+「我的兴趣」按 `text = hobbies.trim() ? \`${who}。${hobbies}\` : who` 拼成单条文本，调 `POST /memory/add` 携带 `{ userId, text, role: 'user' }`。
- **为什么**：用户明确「尽量不改 agent-core 代码」（AGENTS.md 5.5 规约）。拼接逻辑下放到前端，backend 直接收 `text`，零改造。`role: 'user'` 表明这是用户主动写入的初始化记忆（保留前端当前语义）。
- **替代方案**：backend 拼接 — 违反"尽量不改 agent-core"原则。

### Decision 7: 状态查询走 `GET /memory/status`，**只**返回 enabled，**不**返回 hasMemory

- **选择**：新增 `GET /memory/status?userId=xxx`，返回 `{ enabled: boolean }`（**去掉 hasMemory 字段**）。
- **为什么**：用户明确「g1 就没有 hasmemory，默认都有」。入口渲染**只**由 `MEM0_ENABLED` 决定，不查 mem0 是否有记忆。简化 spec，省一次 mem0 调用。
- **mem0 不可达时的处理**：`/memory/status` 内部 try/catch 包 mem0 调用（虽然只查 enabled 几乎不调 mem0，但为对称性），mem0 不可达时返回 `{ enabled: false }`，前端不渲染入口（`enabled === false`）。
- **替代方案**：返回 `{ enabled, hasMemory }` 调 mem0 实际查 — 用户决议：不要 hasMemory，spec 简化。

### Decision 8: 错误处理分级

| 步骤 | 失败处理 |
|---|---|
| `delete` 失败 | 弹错误 toast「清空失败：{error}」，**不**进入初始化窗口 |
| `delete` 成功 + `add` 失败 | 弹警告 toast「记忆已清空但初始化失败，请重试」，初始化窗口保留可继续编辑 |
| `add` 成功 | 弹成功 toast「记忆初始化更新成功」，关闭初始化窗口 |
| 任意步骤用户关闭弹窗 | 取消后续；不清空已写入的部分（delete 已发生） |

## Risks / Trade-offs

- **Risk**: mem0 `/deletemem` 端点可能尚未在生产 mem0 服务部署 → Mitigation: tasks.md 第一步先联调确认；**没部署就 block 等运维部署**（**不**走其他 mem0 删除方式 — 与 Decision 5 一致：只新增 `/deletemem`，不引入 fallback 机制）
- **Risk**: 误点「清除」导致真实用户记忆丢失（不可恢复） → Mitigation: 二次确认弹窗 + 按钮文案「确认清除，不可恢复」+ 不放主色避免误点
- **Risk**: 删除和初始化之间被 agent 检索到「空记忆」产生幻觉回复 → Mitigation: 用户语境下是手动流程，操作期间不会触发 agent 对话（前端不做并发）；且 delete 接口语义上同步生效
- **Risk**: 初始化内容被 mem0 拒收（敏感词 / 超长） → Mitigation: 前端 textarea 限制 500 字；后端把 mem0 错误原文回传前端
- **Trade-off**: 串行两步流程增加总耗时（清空 + 写入各一次网络往返 ~200-500ms）→ 用户场景是「重置」而非高频操作，可接受

## Migration Plan

不需要数据迁移（mem0 无 schema 改动）。上线步骤：

1. 部署 mem0 服务的 `/deletemem` 端点（运维侧，仓库外）
2. 部署 agent-core 新版本（含 `POST /memory/delete` / `GET /memory/status` 端点 + `POST /memory/add` 跨用户守卫补强）
3. 部署 frontend 新版本（ProfileEditModal 增加入口）
4. 灰度：先开 `MEM0_ENABLED=true` 内部账号验证「清除 → 重新初始化」全链路
5. 全量

回滚：移除 ProfileEditModal 入口即可（`MEM0_ENABLED=false` 不影响 — 因为前端走 `/memory/status` 动态判断，直接 disabled 入口）。

## Resolved Decisions（原 Open Questions 已并入上方 Decisions）

| 原 Open Question | 决议 | 位置 |
|---|---|---|
| mem0 `/deletemem` 响应 schema | **与 `/madd` 对齐**：`{ code, message, userid, time }`，失败 code=400 message='用户记忆删除失败' | Decision 5 |
| 「重新初始化」按钮是否分首/末次 | **不分**，统一「清除记忆」（hasMemory 概念移除）| Decision 7 |
| 「我的兴趣」是否拆 tag | **不拆**，单 textarea，前端按 `text = who + (hobbies ? '。' + hobbies : '')` 拼接 | Decision 6 |
