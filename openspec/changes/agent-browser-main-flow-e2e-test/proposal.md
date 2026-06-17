## Why

当前项目缺乏自动化端到端回归测试，每次合并代码后依赖人工验证主流程，效率低且容易遗漏。需要一套可重复执行的 agent-browser 主流程测试用例，覆盖登录、对话管理、Skill 执行（计算/API/SSH）、Skill 生成器、异步任务、模板/自主规划等全量功能，确保合并后核心链路可用。

## What Changes

- 新增一个 agent-browser 主流程 E2E 测试脚本（Shell 脚本），包含完整的 BXDC.bot 功能验证流程
- 测试用例覆盖：
  1. 登录流程（输入用户 ID → 进入聊天界面）
  2. 欢迎语验证（素材库随机选取）
  3. 新建对话 + 发送消息
  4. 内置 compute Skill 执行（数学计算）
  5. 扩展 API Skill 执行 — **GET + Query 参数**（调用聚合新闻接口）
  6. 扩展 API Skill 执行 — **POST + JSON Body**（Mock 测试接口）
  7. 扩展 API Skill 执行 — **表单提交**（formBody）
  8. 扩展 SSH Skill 生成与执行 — **skill_generator 创建**（登录远端服务器执行命令）
  9. 扩展 API Skill 执行 — **skill_generator 创建 POST JSON Body**（Mock 测试接口）
  10. 扩展 API Skill 执行 — **skill_generator 创建 formBody**（表单提交）
  11. 扩展 API Skill 执行 — **skill_generator 创建异步轮询**（提交异步任务 + 轮询状态）
  12. 扩展 API Skill 执行 — **skill_generator 创建长时间异步 SINGLE_CALL**（20s 返回）
  13. Template 类任务 — **skill_generator 创建并执行**
  14. 自主规划（OPENCLAW）— **skill_generator 创建并执行**（编排 compute + juhe_news_query）
  15. 对话自动命名验证
  16. 对话切换验证
- 测试脚本输出通过/失败结果汇总，支持 CI 集成

## Capabilities

### New Capabilities
- `agent-browser-e2e-test`: agent-browser 驱动的端到端主流程测试用例，覆盖登录、对话管理、内置/扩展 Skill 执行（GET/POST/表单/异步轮询/长时间异步/Template/自主规划）、Skill 生成器等全量功能

### Modified Capabilities
<!-- No existing specs modified -->

## Impact

- **新增文件**：`tests/e2e/` 目录下的测试脚本和辅助文件
- **不涉及**业务代码修改、前端改动、后端改动
- **依赖**：agent-browser CLI（`npm install -g agent-browser`）、本地服务运行中（前端 5173 / agent-core 3000 / gateway 18080 / Mock 3456 / MySQL 3306）
- **外部依赖**：聚合新闻 API（`v.juhe.cn`）、SSH 服务器（`39.104.81.41:22`）、Mock Server（`localhost:3456`）
