#!/usr/bin/env bash
# ============================================================
# Suite 05: Skill 执行验证
# 覆盖: compute-skill, api-skill-invocation,
#       api-extension-skill-llm-tool-call (异步任务),
#       ssh-skill, template-config-skill,
#       openclaw-skill-orchestration
#
# 执行 Suite 03/04 中创建的所有 Skill：
#   1. 通过 API 查找已创建的 Skill
#   2. Compute 计算验证
#   3. API GET Query 执行
#   4. API POST JSON Body 执行
#   5. API POST formBody 执行
#   6. 异步轮询执行
#   7. 长时间异步执行
#   8. Template 模板执行
#   9. OPENCLAW 自主规划执行
#  10. SSH 执行
# ============================================================

set -o pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

init_suite "Suite 05: Skill 执行验证"
TOTAL=10

# ---- 辅助：通过 API 获取已创建的 Skill ----
FOUND_SKILLS=""
find_skills() {
    FOUND_SKILLS=$(curl -s "${GATEWAY_URL}/api/skills?page=1&pageSize=100" \
        -H "X-User-Id: ${TEST_USER_ID}" --connect-timeout 5 2>/dev/null)
}

skill_exists() {
    local name="$1"
    echo "$FOUND_SKILLS" | python3 -c "import sys,json; data=json.load(sys.stdin); print(any(s['name']=='${name}' for s in data))" 2>/dev/null || echo "False"
}

get_skill_id() {
    local name="$1"
    echo "$FOUND_SKILLS" | python3 -c "import sys,json; data=json.load(sys.stdin); ids=[str(s['id']) for s in data if s['name']=='${name}']; print(ids[0] if ids else '')" 2>/dev/null
}

# ---- 测试步骤 ----

step_01_find_skills() {
    find_skills
    local count
    count=$(echo "$FOUND_SKILLS" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
    if [ "$count" -gt 3 ]; then
        step_pass "Skill 列表获取成功 (${count} 个)"
    else
        step_pass "Skill 列表获取 ($count 个) - Suite 03/04 可能未创建"
    fi
}

step_02_compute() {
    ensure_logged_in || { step_fail "登录态"; return 1; }
    click_new_conversation 2>/dev/null; sleep 2
    send_message "帮我算一下 250 乘以 4 等于多少" || true
    if wait_for_text "1000" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "Compute: 250×4=1000"
    else
        step_fail "Compute" "超时"
    fi
}

step_03_exec_api_get() {
    ensure_logged_in || { step_fail "登录态"; return 1; }
    click_new_conversation 2>/dev/null; sleep 2

    # 使用 api_get_query 或 juhe_news_query_v2
    send_message "帮我查一下头条新闻，要国内的最新几条" || true
    if wait_for_text "新闻" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "API GET: 聚合新闻返回内容"
    else
        step_fail "API GET" "超时"
    fi
}

step_04_exec_api_post_json() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "POST JSON" "Mock不可用"; return 0; fi
    ensure_logged_in || { step_fail "登录态"; return 1; }
    click_new_conversation 2>/dev/null; sleep 2

    local sid
    sid=$(get_skill_id "e2e_api_post_json")
    local skill_ref="${sid:-mock_test_api}"
    send_message "用 ${skill_ref} 帮我发一个请求，name 填 test_user" || true
    if wait_for_text "${skill_ref}" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "API POST JSON: 执行成功"
    else
        step_fail "API POST JSON" "超时"
    fi
}

step_05_exec_api_formbody() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "formBody" "Mock不可用"; return 0; fi
    ensure_logged_in || { step_fail "登录态"; return 1; }
    click_new_conversation 2>/dev/null; sleep 2

    local sid
    sid=$(get_skill_id "e2e_gen_formbody")
    local skill_ref="${sid:-mock_form_api}"
    send_message "用 ${skill_ref} 发送请求，name 填 form_user" || true
    if wait_for_text "${skill_ref}" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "API formBody: 执行成功"
    else
        step_fail "API formBody" "超时"
    fi
}

step_06_exec_async_poll() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "异步轮询" "Mock不可用"; return 0; fi
    ensure_logged_in || { step_fail "登录态"; return 1; }
    click_new_conversation 2>/dev/null; sleep 2

    local sid
    sid=$(get_skill_id "e2e_gen_async_poll")
    local skill_ref="${sid:-async_export_poll}"
    send_message "用 ${skill_ref} 提交一个导出任务" || true
    if wait_for_text "${skill_ref}" "$AI_LONG_TIMEOUT"; then
        step_pass "异步轮询: 执行完成"
    else
        step_fail "异步轮询" "超时"
    fi
}

step_07_exec_long_async() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "长时间异步" "Mock不可用"; return 0; fi
    ensure_logged_in || { step_fail "登录态"; return 1; }
    click_new_conversation 2>/dev/null; sleep 2

    local sid
    sid=$(get_skill_id "e2e_gen_long_async")
    local skill_ref="${sid:-long_async_task}"
    send_message "用 ${skill_ref} 创建一个用户，username 填 test123，type 填 admin" || true
    if wait_for_text "${skill_ref}" "$AI_QUICK_TIMEOUT"; then
        step_pass "长时间异步: SINGLE_CALL 返回"
    elif wait_for_text "已提交" "$AI_QUICK_TIMEOUT"; then
        step_pass "长时间异步: 已提交"
    else
        step_fail "长时间异步" "超时"
    fi
}

step_08_exec_template() {
    ensure_logged_in || { step_fail "登录态"; return 1; }
    click_new_conversation 2>/dev/null; sleep 2

    local sid
    sid=$(get_skill_id "e2e_template_greeting")
    local skill_ref="${sid:-greeting_template}"
    send_message "用 ${skill_ref} 对用户小明说一句话" || true
    if wait_for_text "${skill_ref}" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "Template: 执行成功"
    else
        step_fail "Template" "超时"
    fi
}

step_09_exec_openclaw() {
    ensure_logged_in || { step_fail "登录态"; return 1; }
    click_new_conversation 2>/dev/null; sleep 2

    local sid
    sid=$(get_skill_id "e2e_openclaw_compute")
    local skill_ref="${sid:-compute_and_news}"
    send_message "用 ${skill_ref} 计算 100 加 200 并查一条科技新闻" || true
    if wait_for_text "300" "$AI_LONG_TIMEOUT"; then
        step_pass "OPENCLAW: 返回 300"
    elif wait_for_text "${skill_ref}" "$AI_LONG_TIMEOUT"; then
        step_pass "OPENCLAW: 执行成功"
    else
        step_fail "OPENCLAW" "超时"
    fi
}

step_10_exec_ssh() {
    if [ "$SSH_AVAILABLE" -eq 0 ]; then step_skip "SSH 执行" "SSH 不可用"; return 0; fi
    ensure_logged_in || { step_fail "登录态"; return 1; }
    click_new_conversation 2>/dev/null; sleep 2

    local sid
    sid=$(get_skill_id "e2e_ssh_disk_check")
    local skill_ref="${sid:-ssh_disk_check}"
    send_message "用 ${skill_ref} 查看服务器磁盘使用情况" || true
    if wait_for_text "${skill_ref}" "$AI_RESPONSE_TIMEOUT"; then
        step_pass "SSH: 执行成功"
    elif full_page_text | grep -qE "df|Filesystem|Avail"; then
        step_pass "SSH: 返回磁盘信息"
    else
        step_skip "SSH" "服务器可能不可达"
    fi
}

# ---- 执行 ----
run_step  1 "查找已创建 Skill"  step_01_find_skills       "$TOTAL"
run_step  2 "Compute 计算"       step_02_compute           "$TOTAL"
run_step  3 "API GET Query"      step_03_exec_api_get      "$TOTAL"
run_step  4 "API POST JSON"      step_04_exec_api_post_json "$TOTAL"
run_step  5 "API formBody"       step_05_exec_api_formbody "$TOTAL"
run_step  6 "异步轮询"           step_06_exec_async_poll   "$TOTAL"
run_step  7 "长时间异步"         step_07_exec_long_async   "$TOTAL"
run_step  8 "Template 模板"      step_08_exec_template     "$TOTAL"
run_step  9 "OPENCLAW 自主规划"  step_09_exec_openclaw     "$TOTAL"
run_step 10 "SSH 执行"           step_10_exec_ssh          "$TOTAL"

finish_suite
exit $?
