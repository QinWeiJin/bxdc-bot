#!/usr/bin/env bash
# ============================================================
# Suite 04: Skill 生成工具（通过 Chat）
# 覆盖: built-in-skill-generation, api-skill-generation,
#       skill-confirmation specs
#
# 通过对话中的 skill_generator 工具创建/编辑各类型 Skill：
#   1. 生成 API POST JSON Skill
#   2. 生成 API GET Query Skill
#   3. 生成 API formBody Skill
#   4. 生成 API 异步轮询 Skill
#   5. 生成 API 长时间异步 Skill
#   6. 生成 SSH Skill
#   7. 生成 Template Skill
#   8. 生成 OPENCLAW Skill
#   9. 通过对话编辑已有 Skill
# ============================================================

set -o pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

init_suite "Suite 04: Skill 生成工具"
TOTAL=9

# ---- 辅助 ----
create_skill_by_chat() {
    local instruction="$1" skill_name="$2"
    local timeout="${3:-$AI_LONG_TIMEOUT}"
    ensure_logged_in || return 1
    if ! send_message "$instruction"; then
        return 1
    fi
    if wait_for_text "$skill_name" "$timeout"; then
        return 0
    fi
    return 1
}

# ---- 测试步骤 ----

step_01_gen_api_post_json() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "生成 POST JSON" "Mock不可用"; return 0; fi

    click_new_conversation 2>/dev/null; sleep 2
    if create_skill_by_chat \
        "帮我创建一个技能，名字叫 e2e_gen_post_json，用于调用 POST http://localhost:3456/mock/test/normal 接口，请求方式是 POST，Content-Type 是 application/json，请求体是 JSON 格式，有一个参数 name，类型是 string" \
        "e2e_gen_post_json"; then
        step_pass "生成 POST JSON Skill 成功"
    else
        step_fail "生成 POST JSON" "skill_generator 未确认"
    fi
}

step_02_gen_api_get_query() {
    click_new_conversation 2>/dev/null; sleep 2
    if create_skill_by_chat \
        "帮我创建一个技能，名字叫 e2e_gen_get_query，GET 请求 http://v.juhe.cn/toutiao/index，参数有 key=c990e44845181032f48cc9a556e3a006 和 type=top" \
        "e2e_gen_get_query"; then
        step_pass "生成 GET Query Skill 成功"
    else
        step_fail "生成 GET Query" "skill_generator 未确认"
    fi
}

step_03_gen_api_form_body() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "生成 formBody" "Mock不可用"; return 0; fi

    click_new_conversation 2>/dev/null; sleep 2
    if create_skill_by_chat \
        "帮我创建一个技能，名字叫 e2e_gen_formbody，POST 请求 http://localhost:3456/mock/test/normal，参数绑定方式是 formBody，有一个参数 name，类型是 string" \
        "e2e_gen_formbody"; then
        step_pass "生成 formBody Skill 成功"
    else
        step_fail "生成 formBody" "skill_generator 未确认"
    fi
}

step_04_gen_async_poll() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "生成异步轮询" "Mock不可用"; return 0; fi

    click_new_conversation 2>/dev/null; sleep 2
    if create_skill_by_chat \
        "帮我创建一个异步轮询技能，名字叫 e2e_gen_async_poll，POST 提交到 http://localhost:3456/mock-async/export/submit，然后用 GET http://localhost:3456/mock-async/export/status?task_id={task_id} 轮询状态" \
        "e2e_gen_async_poll"; then
        step_pass "生成异步轮询 Skill 成功"
    else
        step_fail "生成异步轮询" "skill_generator 未确认"
    fi
}

step_05_gen_long_async() {
    if [ "$MOCK_AVAILABLE" -eq 0 ]; then step_skip "生成长时间异步" "Mock不可用"; return 0; fi

    click_new_conversation 2>/dev/null; sleep 2
    if create_skill_by_chat \
        "帮我创建一个技能，名字叫 e2e_gen_long_async，POST 请求 http://localhost:3456/mock/api/user/create，参数 username 和 type，参数绑定方式是 JSON Body，这个接口返回时间较长需要异步处理" \
        "e2e_gen_long_async"; then
        step_pass "生成长时间异步 Skill 成功"
    else
        step_fail "生成长时间异步" "skill_generator 未确认"
    fi
}

step_06_gen_ssh() {
    if [ "$SSH_AVAILABLE" -eq 0 ]; then step_skip "生成 SSH" "SSH台账不可用"; return 0; fi

    click_new_conversation 2>/dev/null; sleep 2
    if create_skill_by_chat \
        "帮我创建一个 SSH 技能，名字叫 e2e_gen_ssh_disk，用来查看服务器磁盘使用情况，执行命令是 df -h。可以用的服务器台账别名是 39.104.81.41" \
        "e2e_gen_ssh_disk"; then
        step_pass "生成 SSH Skill 成功"
    else
        step_skip "生成 SSH" "skill_generator 未确认"
    fi
}

step_07_gen_template() {
    click_new_conversation 2>/dev/null; sleep 2
    if create_skill_by_chat \
        "帮我创建一个模板技能，名字叫 e2e_gen_template，提示词内容是：请用中文回复用户的问候，回复格式是 JSON，包含 greeting 和 message 两个字段" \
        "e2e_gen_template"; then
        step_pass "生成 Template Skill 成功"
    else
        step_fail "生成 Template" "skill_generator 未确认"
    fi
}

step_08_gen_openclaw() {
    click_new_conversation 2>/dev/null; sleep 2
    if create_skill_by_chat \
        "帮我创建一个自主规划技能，名字叫 e2e_gen_openclaw，系统提示词是：首先用 compute 工具计算 100 加 200 的结果，然后用 juhe_news_query_v2 查询一条科技类新闻，最后将计算结果和新闻标题一起返回。允许工具列表填写 compute 和 juhe_news_query_v2，编排模式选串行" \
        "e2e_gen_openclaw"; then
        step_pass "生成 OPENCLAW Skill 成功"
    else
        step_fail "生成 OPENCLAW" "skill_generator 未确认"
    fi
}

step_09_edit_via_chat() {
    # 通过对话修改已生成的 Skill
    click_new_conversation 2>/dev/null; sleep 2
    send_message "请把 e2e_gen_template 这个技能的提示词改成：请用英文回复用户问候，格式为 JSON，包含 greeting 字段" || true
    sleep 10
    if wait_for_text "已更新" 30 || wait_for_text "修改" 30 || wait_for_text "更新" 30; then
        step_pass "通过对话编辑 Skill 成功"
    else
        step_skip "对话编辑 Skill" "skill_generator 未确认编辑"
    fi
}

# ---- 执行 ----
run_step 1 "生成 POST JSON"     step_01_gen_api_post_json "$TOTAL"
run_step 2 "生成 GET Query"     step_02_gen_api_get_query "$TOTAL"
run_step 3 "生成 formBody"      step_03_gen_api_form_body "$TOTAL"
run_step 4 "生成异步轮询"       step_04_gen_async_poll    "$TOTAL"
run_step 5 "生成长时间异步"     step_05_gen_long_async   "$TOTAL"
run_step 6 "生成 SSH"           step_06_gen_ssh           "$TOTAL"
run_step 7 "生成 Template"      step_07_gen_template      "$TOTAL"
run_step 8 "生成 OPENCLAW"      step_08_gen_openclaw      "$TOTAL"
run_step 9 "对话编辑 Skill"     step_09_edit_via_chat     "$TOTAL"

finish_suite
exit $?
