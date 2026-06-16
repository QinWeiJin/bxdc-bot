#!/usr/bin/env bash
# ============================================================
# BXDC.bot E2E 测试 - 公共工具库
# 被所有 suite-*.sh 和 run-all.sh source 使用
# ============================================================

# --------------- 配置 ---------------
FRONTEND_URL="http://localhost:5173"
AGENT_CORE_URL="http://localhost:3000"
GATEWAY_URL="http://localhost:18080"
MOCK_URL="http://localhost:3456"
TEST_USER_ID="000000"
TEST_NICKNAME="蛋蛋"
ADMIN_PASSWORD="admin123"

# 超时配置（秒）
LOGIN_TIMEOUT=10
AI_RESPONSE_TIMEOUT=90
AI_LONG_TIMEOUT=120
AI_QUICK_TIMEOUT=30
UI_WAIT_TIMEOUT=15
POLL_INTERVAL=2

# 报告输出
REPORT_DIR="tests/e2e/reports"

# --------------- 颜色输出 ---------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'

# --------------- 每个 Suite 独立的状态 ---------------
SUITE_NAME=""
SUITE_PASS=0
SUITE_FAIL=0
SUITE_SKIP=0
SUITE_TOTAL=0
SUITE_STEPS=()
SUITE_STEP_STATUSES=()
SUITE_STEP_DURATIONS=()
SUITE_FAILED_STEPS=()

# ============================================================
#  浏览器操作函数
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
            content=$(full_page_text)
            if [ -z "$content" ]; then
                empty_count=$((empty_count + 1))
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

# ============================================================
#  登录 & 消息
# ============================================================

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
#  Gateway API 辅助
# ============================================================

# 通过 API 创建/更新 Skill（绕过 UI）
gateway_api() {
    local method="$1" path="$2" body="$3"
    local auth_header=""
    if [ "$method" != "GET" ]; then
        auth_header='-H "X-Agent-Token: your-secure-token-here"'
    fi
    if [ -n "$body" ]; then
        eval "curl -s -X ${method} \"${GATEWAY_URL}${path}\" -H \"Content-Type: application/json\" -H \"X-User-Id: ${TEST_USER_ID}\" ${auth_header} -d '${body}' --connect-timeout 5 2>/dev/null"
    else
        eval "curl -s -X ${method} \"${GATEWAY_URL}${path}\" -H \"X-User-Id: ${TEST_USER_ID}\" ${auth_header} --connect-timeout 5 2>/dev/null"
    fi
}

# 通过 API 获取 Skill 列表
get_skills() {
    gateway_api "GET" "/api/skills" ""
}

# 通过 API 获取单个 Skill
get_skill() {
    local id="$1"
    gateway_api "GET" "/api/skills/${id}" ""
}

# 通过 API 删除 Skill
delete_skill() {
    local id="$1"
    gateway_api "DELETE" "/api/skills/${id}" ""
}

# ============================================================
#  结果记录 & 进度显示
# ============================================================

step_pass() {
    local desc="$1"
    SUITE_PASS=$((SUITE_PASS + 1))
    echo -e "     ${GREEN}✓ PASS${NC}: $desc"
}

step_fail() {
    local desc="$1"
    local detail="${2:-}"
    SUITE_FAIL=$((SUITE_FAIL + 1))
    SUITE_FAILED_STEPS+=("${SUITE_NAME}::$desc")
    echo -e "     ${RED}✗ FAIL${NC}: $desc"
    if [ -n "$detail" ]; then
        echo -e "       ${RED}→${NC} $detail"
    fi
}

step_skip() {
    local desc="$1"
    local reason="${2:-外部依赖不可用}"
    SUITE_SKIP=$((SUITE_SKIP + 1))
    echo -e "     ${YELLOW}⊘ SKIP${NC}: $desc ($reason)"
}

# ============================================================
#  Suite 初始化 & 收尾
# ============================================================

init_suite() {
    SUITE_NAME="$1"
    SUITE_PASS=0
    SUITE_FAIL=0
    SUITE_SKIP=0
    SUITE_TOTAL=0
    SUITE_STEPS=()
    SUITE_STEP_STATUSES=()
    SUITE_STEP_DURATIONS=()
    SUITE_FAILED_STEPS=()
    local ts
    ts=$(date '+%H:%M:%S')
    echo ""
    echo -e "${BLUE}╔═══════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}  ${CYAN}${SUITE_NAME}${NC}  ${MAGENTA}${ts}${NC}"
    echo -e "${BLUE}╚═══════════════════════════════════════╝${NC}"
}

run_step() {
    local step_num="$1" step_name="$2" step_func="$3"
    local total="$4"
    SUITE_TOTAL=$((SUITE_TOTAL + 1))

    local ts pad=""
    ts=$(date '+%H:%M:%S')
    if [ "$step_num" -lt 10 ]; then pad="0"; fi

    echo ""
    echo -e "${CYAN}── [${pad}${step_num}/${total}]${NC} ${MAGENTA}${ts}${NC} ${step_name}"

    local start
    start=$(date +%s)

    "$step_func" || true

    local end
    end=$(date +%s)
    local duration=$((end - start))

    SUITE_STEPS+=("$step_name")
    SUITE_STEP_DURATIONS+=("${duration}s")
    echo -e "     ${GREEN}⏱ 耗时: ${duration}s${NC}"
}

finish_suite() {
    local report_file
    # 生成该 suite 的 Markdown 报告
    generate_suite_report

    echo ""
    echo -e "${BLUE}┌──────────────────────────────────────┐${NC}"
    echo -e "${BLUE}│${NC}  ${SUITE_NAME} 结果: ${GREEN}通过 ${SUITE_PASS}${NC}  ${RED}失败 ${SUITE_FAIL}${NC}  ${YELLOW}跳过 ${SUITE_SKIP}${NC}"
    echo -e "${BLUE}└──────────────────────────────────────┘${NC}"

    if [ "$SUITE_FAIL" -gt 0 ]; then
        return 1
    fi
    return 0
}

# 生成 Suite 级别 Markdown 报告
generate_suite_report() {
    mkdir -p "$REPORT_DIR"
    local suite_slug
    suite_slug=$(echo "$SUITE_NAME" | tr ' ' '-' | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9-]//g')
    local report_file="${REPORT_DIR}/${suite_slug}-$(date +%Y%m%d-%H%M%S).md"

    local pass_rate="N/A"
    if [ "$((SUITE_PASS + SUITE_FAIL))" -gt 0 ]; then
        pass_rate=$(echo "scale=1; $SUITE_PASS * 100 / ($SUITE_PASS + $SUITE_FAIL)" | bc 2>/dev/null || echo "N/A")
    fi

    cat > "$report_file" << EOF
# ${SUITE_NAME}

> **时间**: $(date '+%Y-%m-%d %H:%M:%S')
> **测试账号**: ${TEST_USER_ID} (${TEST_NICKNAME})

## 结果

| 指标 | 数据 |
|------|------|
| 通过 | ${SUITE_PASS} |
| 失败 | ${SUITE_FAIL} |
| 跳过 | ${SUITE_SKIP} |
| 通过率 | ${pass_rate}% |

## 步骤详情

| 步骤 | 耗时 |
|------|------|
EOF

    local i
    for i in $(seq 0 $((${#SUITE_STEPS[@]} - 1))); do
        local s_name="${SUITE_STEPS[$i]}"
        local s_dur="${SUITE_STEP_DURATIONS[$i]}"

        # 判断状态
        local icon="✅"
        for fi in "${SUITE_FAILED_STEPS[@]}"; do
            if echo "$fi" | grep -q "::${s_name}\$"; then
                icon="❌"
                break
            fi
        done

        echo "| $s_name | $icon $s_dur |" >> "$report_file"
    done

    if [ ${#SUITE_FAILED_STEPS[@]} -gt 0 ]; then
        cat >> "$report_file" << EOF

## 失败项

EOF
        for fi in "${SUITE_FAILED_STEPS[@]}"; do
            echo "- ${fi}" >> "$report_file"
        done
    fi

    echo "" >> "$report_file"
    echo "> 报告生成时间: $(date '+%Y-%m-%d %H:%M:%S')" >> "$report_file"
}

# ============================================================
#  环境预检（所有 suite 共用）
# ============================================================

check_port() {
    local port="$1" name="$2"
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
    if command -v agent-browser > /dev/null 2>&1; then
        echo -e "  ${GREEN}✓${NC} agent-browser 可用"
    else
        echo -e "  ${RED}✗${NC} agent-browser 不可用"
        all_ok=false
    fi

    echo ""
    if [ "$all_ok" = false ]; then
        echo -e "${YELLOW}⚠ 部分服务不可用${NC}"
    else
        echo -e "${GREEN}✓ 所有服务可用${NC}"
    fi
    echo -e "${CYAN}═══════════════════════════════════════${NC}"

    if [ "$all_ok" = false ]; then
        return 1
    fi
    return 0
}

# MOCK_AVAILABLE 默认值
MOCK_AVAILABLE=1
SSH_AVAILABLE=0

# ============================================================
#  浏览器生命周期
# ============================================================

start_browser() {
    agent-browser close 2>/dev/null || true
    sleep 0.5
}

stop_browser() {
    agent-browser close 2>/dev/null || true
}
