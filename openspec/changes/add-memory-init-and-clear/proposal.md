## Why

`mem0` 服务是用户长期记忆的唯一来源，但目前用户没有任何入口可以**主动清空**或**重新初始化**自己的记忆。常见的真实需求：
- 用户注册时填了个假昵称 / 临时身份，事后想重置为「真实的我」
- 用户的兴趣/职业变化（毕业、换岗、搬家），旧记忆成为噪声
- LLM 误把对话上下文沉淀成了「事实」，需要一键清空

当前 `user-profile` 流程（[user-profile spec](file:///d:/bothome/bxdc-bot/openspec/specs/user-profile/spec.md)）只能改昵称和头像，但头像下面是空白的——正是放「记忆管理」控件的合适位置。

本次改动提供一个**两步流程**（先清空 → 再初始化）放在资料编辑页头像选择区下面，记忆开关关闭时整个功能隐藏。

## What Changes

- 在 `user-profile` 资料编辑页**头像选择器下方**新增一个**低调**的「清除记忆」入口（视觉上不要抢眼，例如用文字按钮 / 灰图标，不放主色 / 不放边框）
- 点击「清除记忆」弹出**危险确认弹窗**（红色标题 + 二次确认输入/按钮），用户确认后调用 `mem0` 的 `/deletemem` 接口按当前用户 ID 删完
- 删除成功后自动弹出**初始化记忆窗口**，包含两个字段：
  - 「我是谁」（多行文本，必填）
  - 「我的兴趣爱好 / 性格特征」（多行文本，可选）
- 用户提交后，把这两段文本组装成 `sentencein` 调 `mem0` 的 `/madd`（`sentenceout` 用一个固定引导语，例如 "已记录用户初始化的个人信息"），把记忆重新种入
- 两步都成功后弹 toast「**记忆初始化更新成功**」
- 任意一步失败：清空成功但初始化失败 → 弹「记忆已清空但初始化失败，请重试」；清空失败 → 弹「清空失败：{error}」，不进入下一步
- **记忆开关仍写在配置文件**（沿用 `agent-core/.env` 的 `MEM0_ENABLED`）：关闭时整个入口和弹窗都不渲染，不是 disabled
- 新增 `mem0` `/deletemem` 接口的 spec 定义（当前 `mem0-integration` 只有 `/msearch` 和 `/madd`）

## Capabilities

### New Capabilities

- `memory-initialization-flow`: 用户在资料编辑页触发的「清空 + 重新初始化记忆」两步流程，涵盖 UI 入口、危险确认、清空弹窗、初始化表单、提交、toast、记忆开关控制可见性。

### Modified Capabilities

- `mem0-integration`: 增加 `/deletemem` 接口的 requirement 段（参数格式与 `/msearch` 对齐：`userid` 必填，校验与现有检索/存储保持一致）。这是 spec-level 行为变更（多了接口），不是单纯实现细节。
- `user-profile`: 增加「资料编辑页提供记忆清空与重新初始化入口」requirement，明确受 `MEM0_ENABLED` 配置开关控制；明确入口放在头像选择器下方，且视觉上足够低调以避免误操作。

## Impact

- 前端 `frontend/src/views/Profile/` / `frontend/src/components/Profile/` — 新增入口按钮、危险确认弹窗、初始化表单弹窗；受 `MEM0_ENABLED` 配置门控
- 前端 store / api 客户端 — 新增 `deleteUserMemory` 调用，对应后端网关新接口
- 后端网关 `backend/skill-gateway/` — 新增代理 `/deletemem` 的 controller（与 `/msearch` `/madd` 同样模式：path prefix + token 校验 + 透传 `userid`）
- `agent-core` — 不直接修改（AGENTS.md 5.5 规约：记忆操作走 Tool 接入 / 网关转发；agent-core 已有 mem0 client，新增删除方法在网关侧）
- 配置文件 `agent-core/.env` — 已存在 `MEM0_ENABLED` 开关，本次只读不增（AGENTS.md 5.2 规约：尽量不增环境变量）
- 受影响能力：`mem0-integration`（加 endpoint）、`user-profile`（加 UI 入口 requirement）
- 依赖：纯前端 + 网关透传，无新增第三方包（AGENTS.md 5.1 规约）
