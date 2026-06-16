## Context

当前系统使用单一 ReactAgent 架构，每次创建 Agent 实例时都会加载所有可用技能，包括用户自定义技能和系统内置技能。这种设计存在以下问题：

1. **性能问题**：每次对话初始化时加载所有技能，导致启动延迟高
2. **资源浪费**：用户可能只需要少数几个技能，但所有技能都被加载到内存
3. **职责不清**：Agent 同时负责任务规划和技能执行，缺乏清晰的职责分离
4. **可观测性差**：前端无法实时查看 Agent 的工具调用过程，只能看到最终结果
5. **数据模型混乱**：skills 表和 system_skills 表字段不一致，维护困难

系统使用 LangGraph 的 ReAct 模式实现 Agent，技能通过 `loadGatewayExtendedTools` 从 Java Skill Gateway 动态加载。当前架构中，AgentFactory 只提供一个 `createAgent` 方法，创建包含所有技能的完整 Agent。

## Goals / Non-Goals

**Goals:**
- 实现两级 Agent 架构，主 Agent 负责规划和协调，子 Agent 负责执行特定技能
- 支持技能按需加载，主 Agent 加载用户自定义技能，子 Agent 动态加载系统技能
- 完整追踪子 Agent 的工具调用历史，返回给前端展示
- 统一 skills 表和 system_skills 表的数据模型，添加 skill_owner_type 字段
- 优化 skill_generator 工具，只在用户明确要求时创建技能

**Non-Goals:**
- 不修改 system_skills 表的结构和逻辑
- 不改变 LangGraph 的底层实现和 ReAct 模式
- 不新增外部依赖或第三方库
- 不修改前端展示逻辑（只提供数据接口）

## Decisions

### 1. 两级 Agent 架构

**决策**：将 AgentFactory 拆分为 `createMainAgent` 和 `createSubAgent` 两个方法。

**理由**：
- 主 Agent 专注于任务规划和技能检索，保持轻量级
- 子 Agent 专注于技能执行，按需加载特定技能
- 职责分离清晰，便于维护和测试

**替代方案**：
- 单一 Agent 动态加载技能：实现复杂，状态管理困难
- 多个专职 Agent（如规划 Agent、执行 Agent）：架构复杂，通信成本高

### 2. 技能按需加载

**决策**：主 Agent 加载用户自定义技能（skill_owner_type=1），子 Agent 加载系统技能（skill_owner_type=2）。

**理由**：
- 用户技能数量少且变化频繁，适合主 Agent 管理
- 系统技能数量多且相对稳定，适合子 Agent 按需加载
- 避免重复加载，提升性能

**替代方案**：
- 所有技能都按需加载：增加网络请求次数
- 所有技能都预加载：无法解决性能问题

### 3. 工具调用结果追踪

**决策**：在 execute_skill_with_context 工具中提取子 Agent 的所有工具调用记录，返回 toolCalls 数组。

**理由**：
- 前端可以实时展示子 Agent 的执行过程
- 便于调试和问题排查
- 不影响现有返回格式（result 字段保持不变）

**替代方案**：
- 通过 SSE 流式传输工具调用结果：实现复杂，需要修改控制器层
- 只返回最终结果：无法满足前端需求

### 4. 数据模型统一

**决策**：在 skills 表添加 skill_owner_type 字段（1=用户技能，2=系统技能），不修改 system_skills 表。

**理由**：
- skills 表已有完整字段结构，只需添加一个字段
- system_skills 表保持不变，避免影响现有逻辑
- 通过 skill_owner_type 区分技能类型，查询时过滤

**替代方案**：
- 统一使用 skills 表：需要迁移 system_skills 数据，风险高
- 统一使用 system_skills 表：需要修改现有查询逻辑，影响面大

### 5. 技能生成工具优化

**决策**：修改 skill_generator 的描述，只在用户明确要求时创建技能。

**理由**：
- 避免自动生成无用技能
- 减少用户困惑
- 保持工具描述清晰

**替代方案**：
- 添加配置开关：增加复杂度
- 完全禁用自动生成：失去灵活性

## Risks / Trade-offs

### 风险 1：子 Agent 创建开销

**风险**：每次调用 execute_skill_with_context 都会创建新的子 Agent，可能增加延迟。

**缓解措施**：
- 子 Agent 只加载必要的技能，减少初始化时间
- 考虑实现子 Agent 缓存（未来优化）

### 风险 2：上下文丢失

**风险**：子 Agent 无法访问主 Agent 的对话历史，可能缺少上下文信息。

**缓解措施**：
- 在 userInput 中传递必要的上下文摘要
- 未来可考虑传递最近几条消息作为上下文

### 风险 3：数据迁移

**风险**：添加 skill_owner_type 字段后，现有数据的 skill_owner_type 为 null，需要处理。

**缓解措施**：
- 字段设置默认值为 1（用户技能）
- 提供数据迁移脚本（可选）

### 权衡 1：性能 vs 灵活性

**权衡**：两级架构提升性能，但增加了系统复杂度。

**选择**：优先考虑性能，因为当前性能问题影响用户体验。

### 权衡 2：完整性 vs 简洁性

**权衡**：返回完整的工具调用历史增加数据量，但提升可观测性。

**选择**：优先考虑可观测性，因为调试和问题排查更重要。

## Migration Plan

### 部署步骤

1. **数据库变更**：
   - 执行 SQL 添加 skill_owner_type 字段
   - 验证字段创建成功

2. **后端部署**：
   - 部署 skill-gateway（包含新的 API 端点）
   - 部署 agent-core（包含两级 Agent 架构）

3. **验证**：
   - 测试主 Agent 加载用户技能
   - 测试子 Agent 加载系统技能
   - 测试工具调用结果追踪
   - 验证前端展示正常

### 回滚策略

1. 如果出现问题，回滚到旧版本代码
2. skill_owner_type 字段可以保留（不影响现有逻辑）
3. 恢复原有的 AgentFactory.createAgent 方法

## Open Questions

1. **子 Agent 缓存**：是否需要实现子 Agent 缓存以减少创建开销？
2. **上下文传递**：是否需要传递主 Agent 的部分对话历史给子 Agent？
3. **数据迁移**：是否需要为现有数据设置 skill_owner_type 的默认值？
4. **性能监控**：如何监控两级架构的性能提升效果？