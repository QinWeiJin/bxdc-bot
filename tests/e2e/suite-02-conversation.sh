#!/usr/bin/env bash
# ============================================================
# Suite 02: 对话管理
# 覆盖: conversation-crud, conversation-switch-guard,
#       avatar (欢迎语), chat-ui specs
# ============================================================

set -o pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

init_suite "Suite 02: 对话管理"
TOTAL=5

step_01_ensure_logged_in() {
    if ensure_logged_in; then
        step_pass "登录态验证通过"
    else
        step_fail "登录态" "无法登录"
    fi
}

step_02_greeting() {
    ensure_logged_in || { step_fail "登录态"; return 1; }

    # 新建对话以看到欢迎语
    click_new_conversation 2>/dev/null
    sleep 3

    local snap
    snap=$(snapshot_text)
    if echo "$snap" | grep -q "$TEST_NICKNAME"; then
        step_pass "欢迎语包含用户昵称 '${TEST_NICKNAME}'"
    else
        step_fail "欢迎语" "未找到 '${TEST_NICKNAME}'"
    fi
}

step_03_new_conversation() {
    ensure_logged_in || { step_fail "登录态"; return 1; }

    if click_new_conversation; then
        sleep 3
        if is_on_login_page; then
            step_fail "新建对话后页面跳回登录页"
            return 1
        fi
        if snapshot_text | grep -q "新对话"; then
            step_pass "新建对话 - sidebar 出现 '新对话'"
        else
            step_fail "新建对话" "sidebar 未出现"
        fi
    else
        step_fail "新建对话" "找不到按钮"
    fi
}

step_04_send_and_verify() {
    ensure_logged_in || { step_fail "登录态"; return 1; }

    send_message "你好，请回复'测试OK'" || true
    if wait_for_text "OK" "$AI_QUICK_TIMEOUT"; then
        step_pass "消息发送并收到回复"
    else
        step_fail "消息回复" "超时 ${AI_QUICK_TIMEOUT}s"
    fi
}

step_05_switch_conversation() {
    ensure_logged_in || { step_skip "对话切换" "登录态丢失"; return 0; }

    local conv_ref
    conv_ref=$(snapshot_text \
        | grep -v "新建对话" | grep -v "新对话" \
        | grep -iE "你好|OK|测试|250|计算|compute" \
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

# ---- 执行 ----
run_step 1 "登录态验证"     step_01_ensure_logged_in   "$TOTAL"
run_step 2 "欢迎语展示"     step_02_greeting           "$TOTAL"
run_step 3 "新建对话"       step_03_new_conversation   "$TOTAL"
run_step 4 "发送消息与回复" step_04_send_and_verify    "$TOTAL"
run_step 5 "对话切换"       step_05_switch_conversation "$TOTAL"

finish_suite
exit $?
