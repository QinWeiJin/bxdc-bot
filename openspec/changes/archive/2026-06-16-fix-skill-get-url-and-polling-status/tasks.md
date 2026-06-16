## 1. Bug 1 修复：GET URL 单次编码

- [ ] 1.1 在 `SkillExecutionService.buildUrlWithQuery` 删掉第二段冗余的 `URLEncoder.encode(entry.getKey())` / `URLEncoder.encode(String.valueOf(entry.getValue()))` 调用块（含紧跟其上的"JDK 1.8 单参数版本"误导注释）
- [ ] 1.2 全仓 grep `URLEncoder.encode(.*)` 单参数版本，确认除 `buildUrlWithQuery` 外没有其他调用方（如果有就一起改成双参数 UTF-8）
- [ ] 1.3 `mvn -s ./settings.xml compile` 通过

## 2. Bug 2 修复：异步轮询 auto-detect status

- [ ] 2.1 在 `AsyncTaskPollingService` 新增私有常量 `STATUS_FIELD_CANDIDATES`（按 design Decision 2 的 8 个候选路径）
- [ ] 2.2 在 `AsyncTaskPollingService` 新增私有常量 `TERMINAL_SUCCESS_VALUES` / `TERMINAL_FAILURE_VALUES`（按 design Decision 2 的白名单）
- [ ] 2.3 改造 `evaluateCompletion(pollResponse, completionJsonPath, completionValue)`：用户配置齐全且值匹配 → 走原逻辑返回 true；否则 fallthrough 到 auto-detect：按候选路径抽 status-like 字段，命中 SUCCESS 白名单返回 true
- [ ] 2.4 改造 `evaluateFailure(pollResponse, completionJsonPath, failedValuesJson)`：同上 fallback 到 auto-detect 失败白名单
- [ ] 2.5 新增私有方法 `autoDetectTerminalStatus(pollResponse)`：返回 `Optional<TerminalStatus>`，`TerminalStatus` 枚举 `SUCCESS` / `FAILURE` / `UNKNOWN`；按候选路径顺序提取，命中 SUCCESS/FAILURE 白名单返回对应枚举值，否则返回 UNKNOWN
- [ ] 2.6 改造 `AsyncTaskPollingScheduler.tick()` 调用链：evaluateCompletion / evaluateFailure 返回 false 但 `autoDetectTerminalStatus` 是 SUCCESS → 复用 COMPLETED 标记链路；是 FAILURE → 复用 FAILED 标记链路
- [ ] 2.7 在 `AsyncPollingAuditLog` 加 `autoDetected` 字段（boolean），auto-detect 命中时记录 + 写 audit log 时记 `autoDetected=true`
- [ ] 2.8 `mvn -s ./settings.xml compile` 通过

## 3. 前端：progressPercent UX 改进

- [ ] 3.1 在 `TaskNotificationBell.vue` 的 `progressPercent` 函数：当 `elapsedSeconds >= maxWaitSeconds * 0.95` 且 status 还是 POLLING/SINGLE_CALLED 时，返回 99 但加 `:title` prop "任务仍在运行中，可能即将完成"
- [ ] 3.2 给通知中心 `<t-progress>` 元素加 `:title` 属性绑定（从 progressPercent 函数返回值映射到提示文案）
- [ ] 3.3 `npm run build` 通过

## 4. 验证与回归测试

- [ ] 4.1 启动 gateway + agent-core + frontend
- [ ] 4.2 烟测 1：调一个 GET 类 API skill，参数含中文（如 `{city: "北京"}`）—— 抓包或 gateway 日志确认 URL 只编码一次
- [ ] 4.3 烟测 2：调一个 async 轮询 skill，配置 completionJsonPath 不填 —— mock pollEndpoint 返回 `{"status":"SUCCESS"}` —— 确认任务从 99% 跳到 COMPLETED
- [ ] 4.4 烟测 3：调一个 async 轮询 skill，配置 completionJsonPath 故意配错（路径不存在）—— mock pollEndpoint 返回 `{"data":{"status":"FINISHED"}}` —— 确认 auto-detect 命中 `$.data.status` 走 COMPLETED
- [ ] 4.5 烟测 4：调一个 async 轮询 skill，pollEndpoint 返回 `{"status":"RUNNING"}`（运行中）—— 确认不误判，继续轮询
- [ ] 4.6 烟测 5（回归保护）：调一个已配好 `completionJsonPath=$.data.status` `completionValue=SUCCESS` 的存量 skill，pollEndpoint 返回对应格式 —— 确认仍走用户配置路径（auto-detect 不抢）
- [ ] 4.7 gateway 日志 / frontend UI 双确认无报错

## 5. 归档

- [ ] 5.1 `npx openspec archive fix-skill-get-url-and-polling-status`
- [ ] 5.2 检查 archive 后的 `openspec/specs/api-skill-invocation/spec.md` 和新 spec `openspec/specs/async-task-polling-completion/spec.md` 内容正确
- [ ] 5.3 commit + push myfork/temp
