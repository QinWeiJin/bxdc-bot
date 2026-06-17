## Why

当前 ReactAgent 的调用方式存在以下问题：
1. 每次创建 Agent 都会加载所有技能，导致初始化开销大
2. 缺乏技能按需加载机制，无法根据任务动态选择技能
3. 主 Agent 和子 Agent 职责不清晰，工具调用结果无法完整传递到前端
4. skills 表和 system_skills 表字段不一致，数据模型混乱

通过重构 ReactAgent 调用方式，实现两级 Agent 架构，提升性能和可维护性。

## What Changes

- **两级 Agent 架构**：拆分为主 Agent（规划协调）和子 Agent（执行特定技能）
- **技能按需加载**：主 Agent 加载用户自定义技能，子 Agent 动态加载系统技能
- **工具调用结果追踪**：execute_skill_with_context 返回子 Agent 的所有工具调用历史
- **数据模型统一**：skills 表添加 skill_owner_type 字段，区分用户技能（1）和系统技能（2）
- **技能生成工具优化**：skill_generator 只在用户明确要求时创建技能，避免自动生成

## Capabilities

### New Capabilities
- `two-tier-agent`: 实现主 Agent 和子 Agent 的两级架构，支持技能按需加载和工具调用结果追踪

### Modified Capabilities
- `agent-core`: 修改 AgentFactory 的创建逻辑，支持 createMainAgent 和 createSubAgent
- `skills-data-model`: 统一 skills 和 system_skills 表结构，添加 skill_owner_type 字段

## Impact

**受影响的代码**：
- `backend/agent-core/src/agent/agent.ts`：AgentFactory 重构
- `backend/agent-core/src/tools/search-tools.ts`：查询系统技能
- `backend/agent-core/src/tools/execute-skill.ts`：返回工具调用历史
- `backend/agent-core/src/tools/skill-generator.ts`：优化创建条件
- `backend/skill-gateway/src/main/resources/schema-mysql.sql`：添加 skill_owner_type 字段
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/entity/Skill.java`：添加字段映射
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/mapper/SkillMapper.java`：添加查询方法
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/SkillService.java`：添加服务方法
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/SkillController.java`：添加查询端点

**API 变更**：
- 新增 `GET /api/skills/by-owner-type?ownerType=1|2` 端点

**依赖变更**：无新增依赖

**系统影响**：
- 提升 Agent 初始化性能（减少不必要的技能加载）
- 改善前端用户体验（实时查看子 Agent 执行过程）
- 统一数据模型，便于维护和扩展