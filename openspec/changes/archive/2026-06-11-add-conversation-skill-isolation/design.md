## Context

当前架构：
- `AgentFactory.createAgent()` 在 agent-core 创建 ReAct Agent 时，调用 `loadGatewayExtendedTools()` 从 Gateway 全量加载所有启用的 Extension Skill
- `loadGatewayExtendedTools()` 内部调 `GET /api/skills` 获得全部 Skill 列表，过滤 `enabled && type === "EXTENSION"`，逐个注册为 LangChain StructuredTool
- Built-in Tool（compute、server_lookup、skill_generator、manage_tasks）在 `agent.ts` 中硬编码注入，不在 `loadGatewayExtendedTools` 中
- 前端 `useChat.sendMessage()` 请求体仅含 `{ instruction, context, history }`，不携带 Skill 相关参数
- DB `conversations.enabled_skills` 字段已建（Phase 1），Java 层已支持读写，但前端从未传值
- SkillHub 当前为 `t-drawer` 内两个 section 直排（Built-in + Extended），无搜索/筛选/Tab

## Goals / Non-Goals

**Goals:**
- 对话级别的 Extension Skill 过滤：不同对话可配置不同的 Extension Skill 集合
- `enabledSkillIds` 为空/未传时完全兼容旧行为（全量加载）
- Built-in Skill 全员可用，不参与过滤
- 前端提供轻量 Skill 配置入口，不与现有 SkillHub 组件耦合
- SkillHub 页面：Tab 切换 Built-in / Extended，默认 Extended
- SkillHub 一级页面：Extended Skills 支持搜索、筛选（激活状态 + 可见性）、单条启用/禁用
- SkillHub 二级管理页面：支持搜索、筛选（激活状态）

**Non-Goals:**
- 不修改 ReAct 循环逻辑和工具描述 draft 流程
- 不修改 Gateway `/api/skills` 接口
- 不做按 Skill 类型（API/SSH/Template）的差异化过滤
- 不改造 ConversationSkillPanel 为 SkillHub 的替代（两者独立）

## Decisions

### D1：过滤点放在 `loadGatewayExtendedTools()` 内部

`AgentFactory.createAgent()` 新增 `enabledSkillIds?: number[]` 参数，透传至 `loadGatewayExtendedTools()`。在 `skills.filter(...)` 之后、循环注册之前，再加一层 `enabledSkillIds` 过滤。

```typescript
// agent.ts — createAgent
export async function createAgent(
  gatewayUrl: string,
  apiToken: string,
  openAiApiKey: string,
  config?: { modelName?, baseUrl?, callbacks?, sessionId? },
  skillManager?: SkillManager,
  userId?: string,
  enabledSkillIds?: number[],  // ← 新增
) { ... }

// java-skills.ts — loadGatewayExtendedTools
export async function loadGatewayExtendedTools(
  gatewayUrl: string,
  apiToken: string,
  userId?: string,
  options?: {
    plannerModel?: any;
    availableTools?: BindableAgentTool[];
    sessionId?: string;
    enabledSkillIds?: number[];  // ← 新增
  },
): Promise<StructuredTool[]> {
  const extensionSkills = skills.filter(
    (skill) => skill.enabled && (skill.type || "").toUpperCase() === "EXTENSION"
  );

  // 按对话配置过滤（enabledSkillIds 为 undefined/[] 时不生效）
  const filteredSkills = options?.enabledSkillIds?.length
    ? extensionSkills.filter((s) => options.enabledSkillIds!.includes(s.id))
    : extensionSkills;
  // ...
}
```

**为何不在 agent.controller 层做**：controller 不应关心工具注册细节，过滤逻辑统一在 `loadGatewayExtendedTools` 内，所有调用方自动受益。

### D2：`enabledSkillIds` 为可选参数，默认兼容旧行为

在 controller 层判断 `enabledSkillIds === undefined || enabledSkillIds.length === 0` 时不传参，`loadGatewayExtendedTools` 视为全量加载。

**为何不用"必传 + 空数组 = 空工具"**：存量调用方（如 confirm/resume 路径的 `invokeExtendedSkillWithConfirmed`）没有 `enabledSkillIds` 上下文，强制传参会导致回归。兼容旧行为是最高优先级。

### D3：前端对话 Skill 面板 — 弹出层而非 Sidebar 内嵌

在 `ConversationSidebar` 每个对话条目上添加齿轮图标，点击弹出 `t-dialog` 包裹的 Skill 勾选列表：
- 数据来源：调用 `GET /api/skills` 获取全量 Skill 列表
- 默认值：新建对话时全选（`enabled_skills = []` 表示全选），面板中所有 checkbox 选中
- 保存：调 `PUT /api/conversations/:id` 更新 `enabled_skills` 字段
- 支持"全选/取消全选"快捷操作

**为何不用 SkillHub 组件**：SkillHub 是为全局 Skill 管理设计的，包含启用/禁用/配置编辑器等复杂逻辑，与"对话级复选框"需求不匹配。独立面板更轻量、更聚焦。

### D4：前端 `useChat.sendMessage()` 传 `enabledSkillIds`

在 `sendMessage()` 调用时，从 `useConversations()` 获取当前对话的 `conversations` 列表，找到对应 `conversation_id` 的 `enabled_skills`，解析为 `number[]` 放入请求体：

```typescript
body: JSON.stringify({
  instruction: finalInstruction,
  context: { userId, sessionId },
  history,
  enabledSkillIds: currentConversationEnabledSkillIds,  // ← 新增
}),
```

### D5：SkillHub Tab 切换

当前 SkillHub 是一个 `t-drawer` 内两个 `<div class="section">` 直排（Built-in 在上，Extended 在下）。改造为 `t-tabs` 组件：

- Tab 1：「Built-in Skills」— 展示 `BUILT_IN_SKILLS` 常量列表（展示型，无开关）
- Tab 2：「Extended Skills」— **默认激活**，展示 Gateway 返回的扩展 Skill + 搜索框 + 筛选 + 开关
- 搜索和筛选仅对 Extended Skills Tab 生效

### D6：搜索与筛选（computed 派生）

不对 API 加新过滤参数，而是在前端对已加载的 `skills` 数组做 computed 派生：

```typescript
const searchQuery = ref('')
const statusFilter = ref<'all' | 'active' | 'inactive'>('all')
const visibilityFilter = ref<'all' | 'private' | 'public'>('all')

const filteredSkills = computed(() => {
  let result = skills.value.filter(s => s.type === 'EXTENSION')
  
  // 搜索
  if (searchQuery.value.trim()) {
    const q = searchQuery.value.trim().toLowerCase()
    result = result.filter(s => s.name.toLowerCase().includes(q))
  }
  
  // 激活状态筛选
  if (statusFilter.value === 'active') result = result.filter(s => s.enabled)
  else if (statusFilter.value === 'inactive') result = result.filter(s => !s.enabled)
  
  // 可见性筛选（仅 Extended Skills 有 visibility 字段）
  if (visibilityFilter.value === 'private') result = result.filter(s => s.visibility === 'PRIVATE')
  else if (visibilityFilter.value === 'public') result = result.filter(s => s.visibility === 'PUBLIC')
  
  return result
})
```

**为何不做服务端筛选**：Skill 总量通常在 50 以内，全量加载后前端过滤即可，不增加 API 复杂度。

### D7：二级管理页搜索与筛选

`SkillManagementModal` 当前展示全量 Skill（含 Built-in）的表格，新增：

- 顶部搜索框：按名称过滤
- 筛选下拉框：全量 / 已激活 / 未激活
- 与一级页面共享相同的 computed 过滤模式

## Risks / Trade-offs

- **[风险] `loadGatewayExtendedTools` 签名变更**：test 文件和 `invokeExtendedSkillWithConfirmed` 也调了这个函数 → **缓解**：`enabledSkillIds` 为可选参数，默认值 `undefined` 保持兼容
- **[风险] 前端 Skill 列表缓存**：打开面板加载 Skill 列表可能延迟 → **缓解**：首次打开面板时加载并缓存在 composable 中，面板关闭不清除
- **[取舍] 对话框编辑 `enabled_skills` 后不立即触发 Agent 重载**：当前设计中 `enabled_skills` 仅在下次发送消息时生效 → 可接受，因为对话切换已 Abort 旧 SSE 流，不会出现新旧配置混合
- **[风险] SkillHub 搜索筛选需刷新列表**：筛选逻辑在前端 computed 派生，无需额外 API 请求，但要求 `skills` 数组包含所有 Skill（已全量获取）→ **缓解**：当前 `fetchSkills()` 已全量加载，无需改动
