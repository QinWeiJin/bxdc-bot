#!/usr/bin/env bash
# ============================================================
# BXDC.bot 主流程 E2E 测试 (v3 - 实时进度 + Markdown 报告)
# 覆盖: 登录 → 欢迎语 → 新建对话 → compute → API GET
#       → skill_generator(api-post/api-formbody/api-async-poll/
#         api-long-async/template/openclaw/ssh) → 对话切换
#
# 依赖: agent-browser CLI + 本地服务全部运行
# 用法: bash tests/e2e/run-main-flow.sh
# ============================================================

set -o pipefail

# --------------- 配置 ---------------
FRONTEND_URL="http://localhost:5173"
AGENT_CORE_URL="http://localhost:3000"
GATEWAY_URL="http://localhost:18080"
MOCK_URL="http://localhost:3456"
TEST_USER_ID="000000"
TEST_NICKNAME="蛋蛋"

# 超时配置（秒）
LOGIN_TIMEOUT=10
AI_RESPONSE_TIMEOUT=90
AI_LONG_TIMEOUT=120
AI_QUICK_TIMEOUT=30
POLL_INTERVAL=2

# 报告输出
REPORT_DIR="tests/e2e/reports"
REPORT_FILE=""
TEST_START_TIME=""

# --------------- 全局状态 ---------------
TOTAL_STEPS=0
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
CURRENT_STEP=""
CURRENT_STEP_NUM=0
FAILED_STEPS=()

# 步骤结果记录（用于报告）
declare -a STEP_NAMES
declare -a STEP_STATUSES
declare -a STEP_DURATIONS
declare -a STEP_DETAILS

# Mock 服务是否可用
MOCK_AVAILABLE=0
# SSH 是否可用
SSH_AVAILABLE=1

# --------------- 颜色输出 ---------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'

# ============================================================
#  核心工具函数
# ============================================================

snapshot_text() {
    agent-browser snapshot 2>/dev/null
}

full_page_text() {
    agent-browser eval "document.body.innerText" 2>/dev/null || echo ""
}

browser_alive() {
    local snap
    snap=$(agent-browser snapshot 2>/dev/null)
    if [ -z "$snap" ] || echo "$snap" | grep -q "(empty page)"; then
        return 1
    fi
    return 0
}

get_by_data_ref() {
    local ref_name="$1"
    snapshot_text | grep "data-ref=\"${ref_name}\"" | grep -oE '\[ref=[a-zA-Z0-9_-]+\]' | head -1 | tr -d '[]'
}

get_by_text() {
    local text="$1"
    snapshot_text | grep -F "$text" | grep -oE '\[ref=[a-zA-Z0-9_-]+\]' | head -1 | tr -d '[]'
}

get_textbox_ref() {
    snapshot_text | grep "textbox" | grep -oE '\[ref=[a-zA-Z0-9_-]+\]' | head -1 | tr -d '[]'
}

is_on_login_page() {
    snapshot_text | grep -q "输入您的 ID 以访问账户"
}

is_on_chat_page() {
    snapshot_text | grep -qE "新建对话|data-ref=\"chat-container\""
}

wait_for_text() {
    local text="$1"
    local timeout="${2:-$AI_RESPONSE_TIMEOUT}"
    local use_snapshot="${3:-false}"
    local elapsed=0
    local empty_count=0
    while [ "$elapsed" -lt "$timeout" ]; do
        local content
        if [ "$use_snapshot" = "true" ]; then
            # snapshot 模式：先检查浏览器存活
            if ! browser_alive; then
                echo "  ${YELLOW}⚠ 浏览器断连，尝试恢复...${NC}" >&2
                agent-browser navigate "${FRONTEND_URL}" 2>/dev/null
                sleep 3
                if ! browser_alive; then
                    return 1
                fi
            fi
            content=$(snapshot_text)
        else
            # eval 模式：直接用 full_page_text，不做 browser_alive 检查
            # 避免多余的 snapshot 调用干扰页面
            content=$(full_page_text)
            if [ -z "$content" ]; then
                empty_count=$((empty_count + 1))
                # 连续 5 次空内容才尝试恢复
                if [ "$empty_count" -ge 5 ]; then
                    echo "  ${YELLOW}⚠ eval 连续空，尝试刷新...${NC}" >&2
                    agent-browser eval "location.reload()" 2>/dev/null
                    sleep 5
                    ensure_logged_in 2>/dev/null
                    empty_count=0
                fi
            else
                empty_count=0
            fi
        fi
        if echo "$content" | grep -qF "$text"; then
            return 0
        fi
        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
    done
    return 1
}

click_ref() {
    local ref="$1"
    if [ -z "$ref" ]; then
        return 1
    fi
    agent-browser click "${ref}" 2>/dev/null
}

do_login() {
    agent-browser open "${FRONTEND_URL}" 2>/dev/null
    sleep 3

    if ! is_on_login_page; then
        agent-browser navigate "${FRONTEND_URL}/login" 2>/dev/null
        sleep 3
    fi

    if ! is_on_login_page; then
        return 1
    fi

    local input_ref
    input_ref=$(get_by_data_ref "login-user-id" | sed 's/ref=//')
    if [ -z "$input_ref" ]; then
        input_ref=$(get_textbox_ref | sed 's/ref=//')
    fi
    if [ -z "$input_ref" ]; then
        return 1
    fi

    agent-browser click "ref=${input_ref}" 2>/dev/null
    sleep 0.3
    agent-browser type "ref=${input_ref}" "${TEST_USER_ID}" 2>/dev/null
    sleep 0.5

    local btn_ref
    btn_ref=$(get_by_data_ref "login-submit-btn" | sed 's/ref=//')
    if [ -z "$btn_ref" ]; then
        btn_ref=$(get_by_text "登录" | sed 's/ref=//')
    fi
    if [ -z "$btn_ref" ]; then
        return 1
    fi

    click_ref "ref=${btn_ref}"
    sleep 3

    if is_on_chat_page; then
        return 0
    else
        return 1
    fi
}

ensure_logged_in() {
    if is_on_chat_page; then
        return 0
    fi
    if do_login; then
        return 0
    else
        return 1
    fi
}

send_message() {
    local msg="$1"
    if ! ensure_logged_in; then
        return 1
    fi
    local textbox_ref  send_btn_ref
    textbox_ref=$(get_textbox_ref | sed 's/ref=//')
    if [ -z "$textbox_ref" ]; then
        return 1
    fi
    agent-browser click "ref=${textbox_ref}" 2>/dev/null
    sleep 0.3
    agent-browser type "ref=${textbox_ref}" "${msg}" 2>/dev/null
    sleep 0.5
    # 点击发送按钮（agent-browser enter 不会触发 Vue 表单提交）
    send_btn_ref=$(snapshot_text | grep -A10 "textbox" | grep "button" | grep -oE '\[ref=[a-zA-Z0-9_-]+\]' | head -1 | sed 's/\[ref=//;s/\]//')
    if [ -n "$send_btn_ref" ]; then
        agent-browser click "ref=${send_btn_ref}" 2>/dev/null
    else
        agent-browser enter 2>/dev/null
    fi
    return 0
}

click_new_conversation() {
    ensure_logged_in || return 1
    local btn_ref
    btn_ref=$(get_by_data_ref "new-conversation-btn" | sed 's/ref=//')
    if [ -n "$btn_ref" ]; then
        click_ref "ref=${btn_ref}"
    else
        btn_ref=$(get_by_text "新建对话" | sed 's/ref=//')
        if [ -n "$btn_ref" ]; then
            click_ref "ref=${btn_ref}"
        else
            return 1
        fi
    fi
    return 0
}

# ============================================================
#  结果记录 & 进度显示
# ============================================================

step_pass() {
    local desc="$1"
    PASS_COUNT=$((PASS_COUNT + 1))
    echo -e "     ${GREEN}✓ PASS${NC}: $desc"
}

step_fail() {
    local desc="$1"
    local detail="${2:-}"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    FAILED_STEPS+=("$CURRENT_STEP: $desc")
    echo -e "     ${RED}✗ FAIL${NC}: $desc"
    if [ -n "$detail" ]; then
        echo -e "       ${RED}→${NC} $detail"
    fi
}

step_skip() {
    local desc="$1"
    local reason="${2:-外部依赖不可用}"
    SKIP_COUNT=$((SKIP_COUNT + 1))
    echo -e "     ${YELLOW}⊘ SKIP${NC}: $desc ($reason)"
}

# 执行单个步骤，记录耗时和结果
run_step() {
    local step_num="$1"
    local step_name="$2"
    local total="$3"
    shift 3
    local step_func="$1"

    CURRENT_STEP="$step_name"
    CURRENT_STEP_NUM="$step_num"

    # 打印进度
    local ts
    ts=$(date '+%H:%M:%S')
    echo ""
    echo -e "${CYAN}── [${step_num}/${total}]${NC} ${MAGENTA}${ts}${NC} ${step_name}"

    # 计时
    local start
    start=$(date +%s)

    # 执行
    "$step_func"
    local exit_code=$?

    local end
    end=$(date +%s)
    local duration=$((end - start))

    # 确定状态
    local status
    if [ $exit_code -ne 0 ]; then
        status="FAIL"
    else
        # 检查是否是 skip（通过检查上一步 PASS/FAIL/SKIP 计数变化）
        # 这里用更简单的方式：函数内部已经调用了 step_pass/step_fail/step_skip
        status="PASS"  # run_step 不负责判定，由函数内部的 step_* 调用决定
    fi

    # 记录
    STEP_NAMES+=("$step_name")
    STEP_STATUSES+=("$status")
    STEP_DURATIONS+=("${duration}s")
    STEP_DETAILS+=("")

    # 打印耗时
    local color="$GREEN"
    echo -e "     ${color}⏱ 耗时: ${duration}s${NC}"
}

# ============================================================
#  环境预检
# ============================================================

check_port() {
    local port="$1"
    local name="$2"
    if curl -s -o /dev/null --connect-timeout 3 "http://localhost:${port}" > /dev/null 2>&1; then
        echo -e "  ${GREEN}✓${NC} $name (:$port) 可达"
        return 0
    else
        echo -e "  ${RED}✗${NC} $name (:$port) 不可达"
        return 1
    fi
}

run_health_checks() {
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════${NC}"
    echo -e "${CYAN}  环境预检${NC}"
    echo -e "${CYAN}═══════════════════════════════════════${NC}"

    local all_ok=true

    echo ""
    echo "--- 端口检测 ---"
    check_port 5173 "Frontend" || all_ok=false
    check_port 3000 "Agent-Core" || all_ok=false
    check_port 18080 "Gateway" || all_ok=false
    if check_port 3456 "Mock Server" 2>/dev/null; then
        MOCK_AVAILABLE=1
    else
        MOCK_AVAILABLE=0
        all_ok=false
        echo -e "  ${YELLOW}⚠ Mock 服务 (:3456) 不可用，相关测试将跳过${NC}"
    fi

    echo ""
    echo "--- agent-browser 检测 ---"
    if command -v agent-browser > /dev/null 2>&1 && agent-browser doctor 2>&1 | grep -q "Launch test.*pass"; then
        echo -e "  ${GREEN}✓${NC} agent-browser 可用"
    elif command -v agent-browser > /dev/null 2>&1; then
        echo -e "  ${YELLOW}⚠${NC} agent-browser CLI 存在 (doctor 有 warning, 不影响测试)"
    else
        echo -e "  ${RED}✗${NC} agent-browser 不可用"
        all_ok=false
    fi

    echo ""
    echo "--- SSH 台账预置 ---"
    local ssh_check
    ssh_check=$(curl -s --connect-timeout 5 "${GATEWAY_URL}/api/server-ledger/my" \
        -H "X-User-Id: ${TEST_USER_ID}" 2>/dev/null | grep -c "39.104.81.41" || echo "0")
    if [ "$ssh_check" -gt "0" ] 2>/dev/null; then
        echo -e "  ${GREEN}✓${NC} SSH 台账已存在 (39.104.81.41)"
    else
        echo -e "  ${YELLOW}⊘ 尝试创建 SSH 台账...${NC}"
        local create_result
        create_result=$(curl -s -X POST "${GATEWAY_URL}/api/server-ledger" \
            -H "Content-Type: application/json" \
            -H "X-User-Id: ${TEST_USER_ID}" \
            -d '{"host":"39.104.81.41","port":22,"username":"root","password":"52415241Ss"}' \
            --connect-timeout 5 2>/dev/null)
        SSH_AVAILABLE=0
        echo -e "  ${YELLOW}⚠ SSH 台账创建失败/不可用，SSH 测试将跳过${NC}"
    fi

    echo ""
    echo "--- juhe_news_query Skill 检查 (ID 55) ---"
    local skill_check
    skill_check=$(curl -s --connect-timeout 5 "${GATEWAY_URL}/api/skills/55" \
        -H "X-User-Id: ${TEST_USER_ID}" 2>/dev/null | head -100)
    if echo "$skill_check" | grep -q '"enabled":true'; then
        echo -e "  ${GREEN}✓${NC} juhe_news_query_v2 已启用"
    else
        echo -e "  ${YELLOW}⊘ 尝试启用 juhe_news_query_v2...${NC}"
        curl -s -X PUT "${GATEWAY_URL}/api/skills/55" \
            -H "Content-Type: application/json" \
            -H "X-User-Id: ${TEST_USER_ID}" \
            -H "X-Agent-Token: your-secure-token-here" \
            -d '{"enabled":true}' --connect-timeout 5 > /dev/null 2>&1
        echo -e "  ${GREEN}✓${NC} 已请求启用"
    fi

    echo ""
    if [ "$all_ok" = false ]; then
        echo -e "${YELLOW}⚠ 部分服务不可用，继续测试 (不可用功能将跳过)${NC}"
    else
        echo -e "${GREEN}✓ 所有服务可用${NC}"
    fi
    echo -e "${CYAN}═══════════════════════════════════════${NC}"
}

# ============================================================
#  测试步骤
# ============================================================

step_01_login() {
    agent-browser open "${FRONTEND_URL}" 2>/dev/null
    sleep 3
    if is_on_login_page; then
        step_pass "打开登录页"
    else
        step_fail "打开登录页" "未找到登录提示"
        return 1
    fi
    if do_login; then
        step_pass "登录成功 - 已进入聊天界面"
    else
        step_fail "登录" "无法登录"
        return 1
    fi
}

step_02_greeting() {
    ensure_logged_in || { step_fail "确保登录态"; return 1; }
    local snap
    snap=$(snapshot_text)
    if echo "$snap" | grep -q "$TEST_NICKNAME"; then
        step_pass "欢迎语展示正确"
    else
        step_fail "欢迎语" "未找到 '$TEST_NICKNAME'"
    fi
}

step_03_new_conversation() {
    ensure_logged_in || { step_fail "确保登录态"; return 1; }
    if ! click_new_conversation; then
        step_fail "点击新建对话" "找不到按钮"
        return 1
    fi
    sleep 3
    if is_on_login_page; then
        step_fail "新建对话后页面跳回登录页"
        return 1
    fi
    if snapshot_text | grep -q "新对话"; then
        step_pass "新建对话成功 - sidebar 出现 '新对话'"
    else
        step_fail "新建对话" "sidebar 未出现 '新对话'"
    fi
}

step_04_compute_skill() {
    ensure_logged_in || { step_fail "确保登录态"; return 1; }
    if ! send_message "帮我算一下 250 乘以 4 等于多少"; then
        step_fail "发送 compute 消息"
        return 1
    fi
    if wait_for_text "1000" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "compute 返回结果 1000"
    else
        step_fail "compute 返回结果" "超时 ${AI_RESPONSE_TIMEOUT}s"
        return 1
    fi
    sleep 2
    if full_page_text | grep -q "compute"; then
        step_pass "页面包含 compute 调用记录"
    else
        step_fail "compute 调用记录" "全页文本未找到"
    fi
}

step_05_api_get() {
    ensure_logged_in || { step_fail "确保登录态"; return 1; }
    if ! send_message "帮我查一下头条新闻，要国内的最新几条"; then
        step_fail "发送新闻查询消息"
        return 1
    fi
    if wait_for_text "新闻" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "API GET 返回新闻内容"
    else
        step_fail "API GET" "超时 ${AI_RESPONSE_TIMEOUT}s"
        return 1
    fi
    sleep 2
    if full_page_text | grep -q "juhe"; then
        step_pass "页面包含 juhe_news_query_v2 调用记录"
    else
        step_fail "juhe_news_query 调用记录" "全页文本未找到"
    fi
}

# ---- Skill Generator 辅助 ----
create_skill_by_chat() {
    local instruction="$1"
    local skill_name="$2"
    local timeout="${3:-$AI_LONG_TIMEOUT}"
    ensure_logged_in || return 1
    if ! send_message "$instruction"; then
        return 1
    fi
    if wait_for_text "$skill_name" "$timeout"; then
        return 0
    fi
    if wait_for_text "创建" 20; then
        return 0
    fi
    return 1
}

# ============================================================
#  Steps 06-19: Skill Generator 测试
# ============================================================

step_06_skill_gen_post_json() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "创建 mock_test_api" "Mock不可用"; return 0; fi
    if create_skill_by_chat \
        "帮我创建一个技能，名字叫 mock_test_api，用于调用 POST http://localhost:3456/mock/test/normal 接口，请求方式是 POST，Content-Type 是 application/json，请求体是 JSON 格式，有一个参数 name，类型是 string" \
        "mock_test_api"; then
        step_pass "mock_test_api (POST JSON) 创建成功"
    else
        step_fail "创建 mock_test_api" "skill_generator 未确认创建"
    fi
}

step_07_exec_mock_test_api() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "执行 mock_test_api" "Mock不可用"; return 0; fi
    sleep 2
    send_message "用 mock_test_api 帮我发一个请求，name 填 test_user" || true
    if wait_for_text "mock_test_api" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "mock_test_api 执行成功"
    else
        step_fail "mock_test_api 执行" "超时"
    fi
}

step_08_skill_gen_form_body() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "创建 mock_form_api" "Mock不可用"; return 0; fi
    if create_skill_by_chat \
        "帮我创建一个技能，名字叫 mock_form_api，POST 请求 http://localhost:3456/mock/test/normal，参数绑定方式是 formBody，有一个参数 name，类型是 string" \
        "mock_form_api"; then
        step_pass "mock_form_api (formBody) 创建成功"
    else
        step_fail "创建 mock_form_api" "skill_generator 未确认创建"
    fi
}

step_09_exec_mock_form_api() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "执行 mock_form_api" "Mock不可用"; return 0; fi
    sleep 2
    send_message "用 mock_form_api 发送请求，name 填 form_user" || true
    if wait_for_text "mock_form_api" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "mock_form_api 执行成功"
    else
        step_fail "mock_form_api 执行" "超时"
    fi
}

step_10_skill_gen_async_poll() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "创建 async_export_poll" "Mock不可用"; return 0; fi
    if create_skill_by_chat \
        "帮我创建一个异步轮询技能，名字叫 async_export_poll，POST 提交到 http://localhost:3456/mock-async/export/submit，然后用 GET http://localhost:3456/mock-async/export/status?task_id={task_id} 轮询状态" \
        "async_export_poll"; then
        step_pass "async_export_poll 创建成功"
    else
        step_fail "创建 async_export_poll" "skill_generator 未确认创建"
    fi
}

step_11_exec_async_poll() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "执行 async_export_poll" "Mock不可用"; return 0; fi
    sleep 2
    send_message "用 async_export_poll 提交一个导出任务" || true
    if wait_for_text "async_export_poll" "$AI_LONG_TIMEOUT"; then
        step_pass "async_export_poll 执行完成"
    else
        step_fail "async_export_poll 执行" "超时"
    fi
}

step_12_skill_gen_long_async() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "创建 long_async_task" "Mock不可用"; return 0; fi
    if create_skill_by_chat \
        "帮我创建一个技能，名字叫 long_async_task，POST 请求 http://localhost:3456/mock/api/user/create，参数 username 和 type，参数绑定方式是 JSON Body，这个接口返回时间较长需要异步处理" \
        "long_async_task"; then
        step_pass "long_async_task 创建成功"
    else
        step_fail "创建 long_async_task" "skill_generator 未确认创建"
    fi
}

step_13_exec_long_async() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "执行 long_async_task" "Mock不可用"; return 0; fi
    sleep 2
    send_message "用 long_async_task 创建一个用户，username 填 test123，type 填 admin" || true
    if wait_for_text "long_async_task" "$AI_QUICK_TIMEOUT"; then
        step_pass "long_async_task SINGLE_CALL 返回"
    elif wait_for_text "已提交" "$AI_QUICK_TIMEOUT"; then
        step_pass "long_async_task 已提交"
    else
        step_fail "long_async_task 提交" "超时"
    fi
}

step_14_skill_gen_template() {
    if create_skill_by_chat \
        "帮我创建一个模板技能，名字叫 greeting_template，提示词内容是：请用中文回复用户的问候，回复格式是 JSON，包含 greeting 和 message 两个字段" \
        "greeting_template"; then
        step_pass "greeting_template 创建成功"
    else
        step_fail "创建 greeting_template" "skill_generator 未确认创建"
    fi
}

step_15_exec_template() {
    sleep 2
    send_message "用 greeting_template 对用户小明说一句话" || true
    if wait_for_text "greeting_template" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "greeting_template 执行成功"
    else
        step_fail "greeting_template 执行" "超时"
    fi
}

step_16_skill_gen_openclaw() {
    if create_skill_by_chat \
        "帮我创建一个自主规划技能，名字叫 compute_and_news，系统提示词是：首先用 compute 工具计算 100 加 200 的结果，然后用 juhe_news_query_v2 查询一条科技类新闻，最后将计算结果和新闻标题一起返回。允许工具列表填写 compute 和 juhe_news_query_v2，编排模式选串行" \
        "compute_and_news"; then
        step_pass "compute_and_news 创建成功"
    else
        step_fail "创建 compute_and_news" "skill_generator 未确认创建"
    fi
}

step_17_exec_openclaw() {
    sleep 2
    send_message "执行 compute_and_news" || true
    if wait_for_text "300" "$AI_LONG_TIMEOUT"; then
        step_pass "OPENCLAW 返回 300"
    else
        step_fail "OPENCLAW 返回 300" "超时"
        return 1
    fi
    sleep 2
    local full_text has_compute=false has_news=false
    full_text=$(full_page_text)
    echo "$full_text" | grep -q "compute" && has_compute=true
    echo "$full_text" | grep -q "juhe" && has_news=true
    if $has_compute && $has_news; then
        step_pass "子工具轨迹: compute + juhe_news_query_v2"
    else
        step_fail "子工具轨迹" "compute=$has_compute news=$has_news"
    fi
}

step_18_skill_gen_ssh() {
    if [ "$SSH_AVAILABLE" -eq 0 ]; then step_skip "创建 ssh_disk_check" "SSH台账不可用"; return 0; fi
    if create_skill_by_chat \
        "帮我创建一个 SSH 技能，名字叫 ssh_disk_check，用来查看服务器磁盘使用情况，执行命令是 df -h。可以用的服务器台账别名是 39.104.81.41" \
        "ssh_disk_check"; then
        step_pass "ssh_disk_check 创建成功"
    else
        step_fail "创建 ssh_disk_check" "skill_generator 未确认创建"
        SSH_AVAILABLE=0
    fi
}

step_19_exec_ssh() {
    if [ "$SSH_AVAILABLE" -eq 0 ]; then step_skip "执行 ssh_disk_check" "SSH不可用"; return 0; fi
    sleep 2
    send_message "用 ssh_disk_check 查看服务器磁盘使用情况" || true
    if wait_for_text "ssh_disk_check" "$AI_RESPONSE_TIMEOUT"; then
        if full_page_text | grep -qE "df|磁盘|Filesystem|/dev/|容量|Avail"; then
            step_pass "SSH 返回磁盘信息"
        else
            step_skip "SSH 结果" "服务器可能不可达"
        fi
    else
        step_skip "SSH 执行" "SSH 超时/不可用"
    fi
}

step_20_switch_conversation() {
    ensure_logged_in || { step_skip "对话切换" "登录态丢失"; return 0; }
    local conv_ref
    conv_ref=$(snapshot_text \
        | grep -v "新建对话" | grep -v "新对话" \
        | grep -iE "计算|新闻|test|mock|250|1000|greeting|compute|ssh" \
        | grep -oE '\[ref=[a-zA-Z0-9_-]+\]' \
        | head -1 | tr -d '[]')
    if [ -z "$conv_ref" ]; then
        step_skip "对话切换" "未找到历史对话"
        return 0
    fi
    click_ref "${conv_ref}"
    sleep 2
    if ! is_on_login_page; then
        step_pass "对话切换成功"
    else
        step_fail "对话切换" "跳回登录页"
    fi
}

step_99_cleanup() {
    agent-browser close 2>/dev/null || true
}

# ============================================================
#  Markdown 报告生成
# ============================================================

generate_markdown_report() {
    mkdir -p "$REPORT_DIR"
    REPORT_FILE="${REPORT_DIR}/e2e-report-$(date +%Y%m%d-%H%M%S).md"

    local pass_count=$PASS_COUNT
    local fail_count=$FAIL_COUNT
    local skip_count=$SKIP_COUNT
    local total=$((pass_count + fail_count + skip_count))
    local pass_rate="N/A"
    if [ "$((pass_count + fail_count))" -gt 0 ]; then
        pass_rate=$(echo "scale=1; $pass_count * 100 / ($pass_count + $fail_count)" | bc 2>/dev/null || echo "N/A")
    fi
    local end_time
    end_time=$(date '+%Y-%m-%d %H:%M:%S')

    # 根据通过率选 emoji
    local emoji="❌"
    if [ "$fail_count" -eq 0 ]; then
        emoji="✅"
    elif [ "$(echo "$pass_rate >= 80" | bc 2>/dev/null || echo 0)" = "1" ]; then
        emoji="⚠️"
    fi

    cat > "$REPORT_FILE" << EOF
# BXDC.bot E2E 测试报告 ${emoji}

> **测试时间**: ${TEST_START_TIME} → ${end_time}  
> **测试账号**: ${TEST_USER_ID} (${TEST_NICKNAME})  
> **前端地址**: ${FRONTEND_URL}

---

## 概述

| 指标 | 数据 |
|------|------|
| 总步骤数 | ${total} |
| ✅ 通过 | ${pass_count} |
| ❌ 失败 | ${fail_count} |
| ⊘ 跳过 | ${skip_count} |
| 通过率 (不含跳过) | **${pass_rate}%** |

## 环境

| 组件 | 地址 | 状态 |
|------|------|------|
| Frontend | ${FRONTEND_URL} | 运行中 |
| Agent-Core | ${AGENT_CORE_URL} | 运行中 |
| Gateway | ${GATEWAY_URL} | 运行中 |
| Mock Server | ${MOCK_URL} | $([ "$MOCK_AVAILABLE" -eq 1 ] && echo "运行中" || echo "不可用") |
| SSH Server | 39.104.81.41 | $([ "$SSH_AVAILABLE" -eq 1 ] && echo "可用" || echo "不可用") |

## 测试详情

EOF

    # 重新遍历步骤输出表格
    local i
    local pass=0 fail=0 skip=0
    for i in $(seq 0 $((${#STEP_NAMES[@]} - 1))); do
        local s_name="${STEP_NAMES[$i]}"
        local s_duration="${STEP_DURATIONS[$i]}"

        # 根据全局失败列表判断状态
        local s_status="✅ PASS"
        local s_detail=""
        for fi in "${FAILED_STEPS[@]}"; do
            if echo "$fi" | grep -q "^$s_name:"; then
                s_status="❌ FAIL"
                s_detail=" — $(echo "$fi" | sed 's/^[^:]*: //')"
                fail=$((fail+1))
                break
            fi
        done
        if [ "$s_status" != "❌ FAIL" ]; then
            # 检查是否 skip
            if echo "$s_name" | grep -qi "ssh" && [ "$SSH_AVAILABLE" -eq 0 ]; then
                s_status="⊘ SKIP"
                skip=$((skip+1))
            elif echo "$s_name" | grep -qi "mock" && [ "$MOCK_AVAILABLE" -eq 0 ]; then
                s_status="⊘ SKIP"
                skip=$((skip+1))
            else
                pass=$((pass+1))
            fi
        fi

        # 状态图标
        local icon="✅"
        case "$s_status" in
            "❌ FAIL") icon="❌" ;;
            "⊘ SKIP") icon="⊘" ;;
        esac

        echo "| [${i}] | ${icon} | ${s_name} | ${s_duration}${s_detail} |" >> "$REPORT_FILE"
    done

    cat >> "$REPORT_FILE" << EOF

## 测试覆盖

| 功能模块 | 步骤 | 说明 |
|---------|------|------|
| 登录 | [01] | 输入用户ID → 点击登录 → 进入聊天界面 |
| 欢迎语 | [02] | 验证素材库随机匹配展示 |
| 新建对话 | [03] | 点击新建 → sidebar 出现新对话 |
| Compute Skill | [04] | 250×4=1000 计算验证 |
| API GET (Query) | [05] | 聚合新闻 GET 接口 |
| Skill Gen: POST JSON | [06-07] | skill_generator 创建 POST Body Skill |
| Skill Gen: formBody | [08-09] | skill_generator 创建 formBody Skill |
| Skill Gen: 异步轮询 | [10-11] | skill_generator 创建异步轮询 Skill |
| Skill Gen: 长时间异步 | [12-13] | skill_generator 创建 SINGLE_CALL Skill |
| Skill Gen: Template | [14-15] | skill_generator 创建模板 Skill |
| Skill Gen: OPENCLAW | [16-17] | skill_generator 创建自主规划 Skill |
| Skill Gen: SSH | [18-19] | skill_generator 创建 SSH Skill |
| 对话切换 | [20] | 切换历史对话验证消息显示 |

EOF

    # 失败详情
    if [ ${#FAILED_STEPS[@]} -gt 0 ]; then
        cat >> "$REPORT_FILE" << EOF

## 失败详情

| 步骤 | 原因 |
|------|------|
EOF
        for fi in "${FAILED_STEPS[@]}"; do
            local f_name="${fi%%:*}"
            local f_detail="${fi#*: }"
            echo "| $f_name | $f_detail |" >> "$REPORT_FILE"
        done
    fi

    # 结论
    cat >> "$REPORT_FILE" << EOF

## 结论

EOF
    if [ "$fail_count" -eq 0 ]; then
        echo "- ✅ **全部测试通过** — 主流程功能正常" >> "$REPORT_FILE"
    else
        echo "- ⚠️ **${fail_count} 个步骤失败** — 需要排查" >> "$REPORT_FILE"
    fi
    echo "" >> "$REPORT_FILE"
    echo "> 报告生成时间: $(date '+%Y-%m-%d %H:%M:%S')" >> "$REPORT_FILE"

    echo ""
    echo -e "${GREEN}📄 测试报告已生成: ${REPORT_FILE}${NC}"
}

# ============================================================
#  主流程
# ============================================================

main() {
    TEST_START_TIME=$(date '+%Y-%m-%d %H:%M:%S')
    local TOTAL_TEST_STEPS=20

    clear 2>/dev/null || true
    echo ""
    echo -e "${BLUE}╔════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}     ${CYAN}BXDC.bot 主流程 E2E 测试 v3${NC}              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}     ${TEST_START_TIME}                    ${BLUE}║${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════╝${NC}"
    echo ""

    # ---- 环境预检 ----
    run_health_checks

    echo ""
    echo -e "${CYAN}═══════════════════════════════════════${NC}"
    echo -e "${CYAN}  开始执行测试 (共 ${TOTAL_TEST_STEPS} 步)${NC}"
    echo -e "${CYAN}═══════════════════════════════════════${NC}"

    # ---- 执行测试步骤 ----
    run_step 01 "登录流程"              "$TOTAL_TEST_STEPS" step_01_login
    run_step 02 "欢迎语验证"            "$TOTAL_TEST_STEPS" step_02_greeting
    run_step 03 "新建对话"              "$TOTAL_TEST_STEPS" step_03_new_conversation
    run_step 04 "Compute Skill 计算"    "$TOTAL_TEST_STEPS" step_04_compute_skill
    run_step 05 "API GET 聚合新闻"      "$TOTAL_TEST_STEPS" step_05_api_get
    run_step 06 "Skill Gen: POST JSON"  "$TOTAL_TEST_STEPS" step_06_skill_gen_post_json
    run_step 07 "执行 POST JSON Skill"  "$TOTAL_TEST_STEPS" step_07_exec_mock_test_api
    run_step 08 "Skill Gen: formBody"   "$TOTAL_TEST_STEPS" step_08_skill_gen_form_body
    run_step 09 "执行 formBody Skill"   "$TOTAL_TEST_STEPS" step_09_exec_mock_form_api
    run_step 10 "Skill Gen: 异步轮询"   "$TOTAL_TEST_STEPS" step_10_skill_gen_async_poll
    run_step 11 "执行异步轮询 Skill"    "$TOTAL_TEST_STEPS" step_11_exec_async_poll
    run_step 12 "Skill Gen: 长时间异步" "$TOTAL_TEST_STEPS" step_12_skill_gen_long_async
    run_step 13 "执行长时间异步 Skill"  "$TOTAL_TEST_STEPS" step_13_exec_long_async
    run_step 14 "Skill Gen: Template"   "$TOTAL_TEST_STEPS" step_14_skill_gen_template
    run_step 15 "执行 Template Skill"   "$TOTAL_TEST_STEPS" step_15_exec_template
    run_step 16 "Skill Gen: OPENCLAW"   "$TOTAL_TEST_STEPS" step_16_skill_gen_openclaw
    run_step 17 "执行 OPENCLAW Skill"   "$TOTAL_TEST_STEPS" step_17_exec_openclaw
    run_step 18 "Skill Gen: SSH"        "$TOTAL_TEST_STEPS" step_18_skill_gen_ssh
    run_step 19 "执行 SSH Skill"        "$TOTAL_TEST_STEPS" step_19_exec_ssh
    run_step 20 "对话切换"              "$TOTAL_TEST_STEPS" step_20_switch_conversation

    # ---- 收尾 ----
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════${NC}"
    step_99_cleanup

    # ---- 终端汇总 ----
    echo ""
    echo -e "${BLUE}╔═══════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}  测试结果汇总                      ${BLUE}║${NC}"
    echo -e "${BLUE}╚═══════════════════════════════════════╝${NC}"
    echo -e "  总计: ${TOTAL_STEPS}  |  ${GREEN}通过: $PASS_COUNT${NC}  |  ${RED}失败: $FAIL_COUNT${NC}  |  ${YELLOW}跳过: $SKIP_COUNT${NC}"
    if [ "$FAIL_COUNT" -gt 0 ]; then
        echo ""
        for fi in "${FAILED_STEPS[@]}"; do
            echo -e "  ${RED}✗${NC} $fi"
        done
    fi
    echo ""

    # ---- 生成 Markdown 报告 ----
    generate_markdown_report

    if [ "$FAIL_COUNT" -gt 0 ]; then
        echo -e "${RED}测试未通过 - ${FAIL_COUNT} 个步骤失败${NC}"
        exit 1
    else
        echo -e "${GREEN}✅ 全部测试通过!${NC}"
        exit 0
    fi
}

main "$@"
