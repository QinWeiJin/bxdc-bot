## 1. 数据库变更

- [x] 1.1 在 skills 表添加 skill_owner_type 字段（TINYINT(1)，默认值 1）
- [x] 1.2 验证字段创建成功

## 2. 后端实体类和 Mapper

- [x] 2.1 在 Skill.java 添加 skillOwnerType 字段及 getter/setter 方法
- [x] 2.2 在 SkillMapper.java 添加 findBySkillOwnerType() 方法
- [x] 2.3 在 SkillMapper.java 添加 findBySkillOwnerTypeAndEnabledIsTrue() 方法

## 3. 后端 Service 和 Controller

- [x] 3.1 在 SkillService.java 添加 listSkillsByOwnerType() 方法
- [x] 3.2 在 SkillController.java 添加 GET /api/skills/by-owner-type 端点
- [x] 3.3 验证 API 端点返回正确的技能列表

## 4. Agent 核心逻辑

- [x] 4.1 在 AgentFactory 添加 createMainAgent() 方法
- [x] 4.2 在 AgentFactory 添加 createSubAgent() 方法
- [x] 4.3 修改 loadGatewayExtendedTools() 支持 ownerType 参数
- [x] 4.4 在 agent.controller.ts 使用 createMainAgent() 替代 createAgent()

## 5. 工具实现

- [x] 5.1 修改 search-tools.ts 调用 /api/skills/by-owner-type?ownerType=2
- [x] 5.2 修改 execute-skill.ts 返回 toolCalls 数组
- [x] 5.3 修改 skill-generator.ts 优化创建条件描述

## 6. 测试和验证

- [x] 6.1 测试主 Agent 加载用户技能（ownerType=1）
- [x] 6.2 测试子 Agent 加载系统技能（ownerType=2）
- [x] 6.3 测试工具调用结果追踪（toolCalls 返回）
- [x] 6.4 测试 skill_generator 只在用户明确要求时创建
- [x] 6.5 验证前端展示正常