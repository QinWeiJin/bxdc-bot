## Context

当前项目主流程验证依赖人工操作浏览器，流程繁琐且不可重复。agent-browser 提供了 CLI 驱动的浏览器自动化能力，ref-based 元素选择对动态页面兼容性好。本设计将全量主流程拆解为有序步骤，封装为 Shell 脚本，每步通过 agent-browser 执行操作 + snapshot 抓取页面状态 + grep 验证预期内容。

测试环境：
- 前端 `http://localhost:5173`
- agent-core `http://localhost:3000`
- gateway `http://localhost:18080`
- 测试账号：`000000`（蛋蛋，🦈 头像）

## Goals / Non-Goals

**Goals:**
- 覆盖登录 → 欢迎语 → 新建对话 → compute Skill → API Skill（聚合新闻）→ SSH Skill 全链路
- 每步有明确的通过/失败判定（通过 snapshot + grep 验证关键文本）
- Shell 脚本可直接执行，适合 CI 集成
- 外部 API 调用有超时和重试保护

**Non-Goals:**
- 不修改业务代码
- 不做性能测试
- 不做并发/压力测试
- 不做跨浏览器兼容测试（仅 Chrome）

## Decisions

### D1：测试脚本格式 → 单个 Shell 脚本 + 步骤函数

每个测试步骤封装为独立函数，函数名含编号（如 `step_01_login`），主流程按序调用。失败时打印步骤名 + 错误信息并退出。

**替代方案**：Jest/Playwright → 拒绝，增加项目依赖且与 agent-browser 风格不一致。

### D2：断言方式 → agent-browser snapshot + grep

每步执行后 `snapshot` 获取页面可访问树，grep 匹配预期文本。匹配到即通过，未匹配到打印当前 snapshot 并退出。

示例：
```bash
agent-browser snapshot | grep "100 + 200 等于" && echo "PASS" || exit 1
```

### D3：异步等待策略 → 固定 sleep + 轮询 snapshot

LLM 响应时间不确定（3-30s），采用固定初始等待 + 轮询 snapshot 直到出现预期文本或超时。

```bash
wait_for_text() {
    local text="$1"
    local timeout="${2:-60}"
    for i in $(seq 1 "$timeout"); do
        agent-browser snapshot | grep -q "$text" && return 0
        sleep 1
    done
    return 1
}
```

### D4：SSH Skill 前置条件 → 测试脚本内自动创建 SSH 台账记录

SSH Skill 依赖 `server_ledger` 表中的服务器台账。测试前通过 curl 调 API 确保台账存在。

### D5：API Skill 前置条件 → 测试脚本内确保 juhe_news_query Skill 存在并启用

通过 curl + MySQL 检查 id=41 的 juhe_news_query Skill 状态，未启用则先启用。

## Risks / Trade-offs

- **[Risk] 聚合新闻 API 不可用** → 超时 10s 后跳过，标记为 SKIP 而非 FAIL
- **[Risk] SSH 目标服务器离线** → 同 API 处理，超时后 SKIP
- **[Risk] LLM 推理时间过长** → 最长等待 90s，超时 FAIL
- **[Risk] agent-browser 与 Chrome 版本不兼容** → 测试前执行 `agent-browser doctor` 预检
- **[Trade-off] UI 调整后 snapshot ref 可能变化** → 使用文本内容 grep 而非 ref 做断言，更健壮
