## 1. 目录与基础设施

- [x] 1.1 创建 `tests/e2e/` 目录并添加 `.gitkeep`
- [x] 1.2 创建 `tests/e2e/run-main-flow.sh` 主测试脚本骨架（shebang、颜色输出、结果计数器）
- [x] 1.3 实现 `wait_for_text()` 工具函数（轮询 snapshot + grep，最长 90s 超时，支持自定义 timeout）
- [x] 1.4 实现 `step_pass` / `step_fail` / `step_skip` 结果记录函数
- [x] 1.5 实现 `print_summary()` 汇总输出函数

## 2. 环境预检

- [x] 2.1 实现端口检测：curl 检查 5173 / 3000 / 18080 / 3456 端口
- [x] 2.2 实现 agent-browser doctor 预检
- [x] 2.3 实现 SSH 台账预置：通过 curl 调 API 确保 `39.104.81.41` 台账存在
- [x] 2.4 实现 juhe_news_query Skill 启用检查：通过 curl 调 API 确保 id=41 Skill 为 enabled
- [x] 2.5 实现 Mock 服务可用性检查（`:3456`）

## 3. 登录测试

- [x] 3.1 实现 `step_01_login`：打开登录页 → 输入 000000 → 点击登录 → 验证进入聊天界面

## 4. 欢迎语验证

- [x] 4.1 实现 `step_02_greeting`：snapshot 验证欢迎语包含 "蛋蛋" 和非空

## 5. 新建对话

- [x] 5.1 实现 `step_03_new_conversation`：点击 "新建对话" → 等待 → 验证 sidebar 出现 "新对话" → 验证新欢迎语

## 6. Compute Skill 执行

- [x] 6.1 实现 `step_04_compute_skill`：输入 "帮我算一下 250 乘以 4 等于多少" → 发送 → 等待 AI 响应 → 验证回复含 "1000" → 验证 compute Skill 调用记录 → 验证对话自动命名

## 7. API Skill GET 执行（聚合新闻 Query 参数）

- [x] 7.1 实现 `step_05_api_get`：输入 "帮我查一下头条新闻，要国内的最新几条" → 发送 → 等待 → 验证回复含新闻内容 → 验证 juhe_news_query 调用记录

## 8. Skill 生成器 — POST JSON Body API Skill

- [x] 8.1 实现 `step_06_skill_gen_post_json`：输入创建 mock_test_api Skill 的指令 → 发送 → 等待 → 验证创建成功
- [x] 8.2 实现 `step_07_exec_mock_test_api`：输入 "用 mock_test_api 发请求" → 发送 → 等待 → 验证调用结果

## 9. Skill 生成器 — formBody API Skill

- [x] 9.1 实现 `step_08_skill_gen_form_body`：输入创建 mock_form_api（formBody）指令 → 发送 → 等待 → 验证创建成功
- [x] 9.2 实现 `step_09_exec_mock_form_api`：输入 "用 mock_form_api 发送请求" → 发送 → 等待 → 验证调用结果

## 10. 异步轮询 API 任务

- [x] 10.1 实现 `step_10_skill_gen_async_poll`：输入创建 async_export_poll Skill → 发送 → 等待 → 验证创建成功
- [x] 10.2 实现 `step_11_exec_async_poll`：输入 "用 async_export_poll 提交导出任务" → 发送 → 等待（最长 120s）→ 验证任务完成

## 11. 长时间返回异步任务（SINGLE_CALL）

- [x] 11.1 实现 `step_12_skill_gen_long_async`：输入创建 long_async_task Skill → 发送 → 等待 → 验证创建成功
- [x] 11.2 实现 `step_13_exec_long_async`：输入 "用 long_async_task 创建用户" → 发送 → 等待 → 验证返回异步任务 ID

## 12. Template 类任务

- [x] 12.1 实现 `step_14_skill_gen_template`：输入创建 greeting_template Skill → 发送 → 等待 → 验证创建成功
- [x] 12.2 实现 `step_15_exec_template`：输入 "用 greeting_template 对小明说一句话" → 发送 → 等待 → 验证返回 JSON 格式问候

## 13. 自主规划（OPENCLAW）Skill

- [x] 13.1 实现 `step_16_skill_gen_openclaw`：输入创建 compute_and_news Skill → 发送 → 等待 → 验证创建成功
- [x] 13.2 实现 `step_17_exec_openclaw`：输入 "执行 compute_and_news" → 发送 → 等待（最长 120s）→ 验证返回 300 + 新闻标题 → 验证子工具轨迹

## 14. SSH Skill 生成与执行

- [x] 14.1 实现 `step_18_skill_gen_ssh`：输入创建 ssh_disk_check Skill 指令 → 发送 → 等待 → 验证创建成功
- [x] 14.2 实现 `step_19_exec_ssh`：输入 "用 ssh_disk_check 查看服务器磁盘使用" → 发送 → 等待 → 验证含磁盘信息 → SSH 不可用时 SKIP

## 15. 对话切换

- [x] 15.1 实现 `step_20_switch_conversation`：点击历史对话 → 验证历史消息显示

## 16. 收尾与汇总

- [x] 16.1 实现 `step_99_cleanup`：agent-browser close
- [x] 16.2 实现 `print_summary`：输出每步 PASS/FAIL/SKIP 及整体通过率
- [x] 16.3 脚本以非零退出码退出当存在 FAIL 步骤

## 17. 验证

- [ ] 17.1 本地启动全部服务，执行 `bash tests/e2e/run-main-flow.sh`
- [ ] 17.2 验证所有步骤 PASS（SSH 可 SKIP，Mock 服务依赖的步骤在 Mock 不可用时 SKIP）
- [ ] 17.3 验证 summary 输出格式正确
