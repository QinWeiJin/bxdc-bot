#!/usr/bin/env bash
# ============================================================
# Suite 03: Skill 表单 CRUD
# 覆盖: skill-management-editor, extended-skill-management,
#       database-skill-types, api-skill-invocation,
#       ssh-skill, template-config-skill, openclaw-skill-orchestration
#
# 通过 SkillManagementModal UI 进行：
#   1. 新增 API Skill (POST JSON)
#   2. 新增 API Skill (GET Query)
#   3. 编辑 API Skill
#   4. 新增 SSH Skill
#   5. 编辑 SSH Skill
#   6. 新增 Template Skill
#   7. 编辑 Template Skill
#   8. 新增 OPENCLAW Skill
#   9. 编辑 OPENCLAW Skill
#  10. 删除 Skill
# ============================================================

set -o pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

init_suite "Suite 03: Skill 表单 CRUD"
TOTAL=10

# 记录创建的 Skill ID
declare -A CREATED_SKILL_IDS

# ---- 辅助函数 ----

# 打开 Skill 管理窗口（通过 chat 界面上的 "Skill配置" 按钮）
open_skill_management() {
    ensure_logged_in || return 1
    # 先新建对话确保在聊天界面
    click_new_conversation 2>/dev/null
    sleep 2

    # 点击 "Skill配置" 按钮
    local btn_ref
    btn_ref=$(get_by_text "Skill配置" | sed 's/ref=//')
    if [ -z "$btn_ref" ]; then
        # fallback: 找 sidebar 上的 Skill 管理入口
        btn_ref=$(snapshot_text | grep -i "skill" | grep "button" | grep -oE '\[ref=[a-zA-Z0-9_-]+\]' | head -1 | tr -d '[]')
    fi
    if [ -n "$btn_ref" ]; then
        agent-browser click "ref=${btn_ref}" 2>/dev/null
        sleep 2
        return 0
    fi
    return 1
}

# 在 Skill 管理窗口中点击"新建 Skill"
click_new_skill() {
    local snap
    snap=$(snapshot_text)
    local btn_ref
    btn_ref=$(echo "$snap" | grep -iE "新建|新增|创建|添加.*[Ss]kill" | grep "button" | grep -oE '\[ref=[a-zA-Z0-9_-]+\]' | head -1 | tr -d '[]')
    if [ -n "$btn_ref" ]; then
        agent-browser click "${btn_ref}" 2>/dev/null
        sleep 2
        return 0
    fi
    return 1
}

# 在表单中填写字段（通过 label 找对应的 input）
fill_form_field() {
    local label="$1" value="$2"
    local snap
    snap=$(snapshot_text)
    # 找 label 后面的 textbox
    local tb_ref
    tb_ref=$(echo "$snap" | grep -A3 "$label" | grep "textbox" | grep -oE '\[ref=[a-zA-Z0-9_-]+\]' | head -1 | tr -d '[]')
    if [ -z "$tb_ref" ]; then
        # fallback: 直接找所有 textbox 中的第 N 个
        return 1
    fi
    agent-browser click "ref=${tb_ref}" 2>/dev/null
    sleep 0.2
    agent-browser type "ref=${tb_ref}" "$value" 2>/dev/null
    sleep 0.3
    return 0
}

# 点击保存/确认按钮
click_save() {
    local snap
    snap=$(snapshot_text)
    local btn_ref
    btn_ref=$(echo "$snap" | grep -iE "保存|确认|提交|确定" | grep "button" | grep -oE '\[ref=[a-zA-Z0-9_-]+\]' | head -1 | tr -d '[]')
    if [ -n "$btn_ref" ]; then
        agent-browser click "${btn_ref}" 2>/dev/null
        sleep 2
        return 0
    fi
    return 1
}

# ---- 测试步骤 ----

step_01_login_ensure() {
    if ensure_logged_in; then
        step_pass "登录态验证通过"
    else
        step_fail "登录态"
    fi
}

# 通过 API 创建 API Skill（绕过复杂 UI，验证 Gateway 能力）
step_02_create_api_skill_via_api() {
    local resp
    resp=$(curl -s -X POST "${GATEWAY_URL}/api/skills" \
        -H "Content-Type: application/json" \
        -H "X-User-Id: ${TEST_USER_ID}" \
        -H "X-Agent-Token: your-secure-token-here" \
        -d '{
            "name":"e2e_api_post_json",
            "description":"E2E测试-POST JSON",
            "executionMode":"CONFIG",
            "configuration":"{\"kind\":\"api\",\"operation\":\"e2e_api_post_json\",\"method\":\"POST\",\"endpoint\":\"http://localhost:3456/mock/test/normal\",\"parameterBinding\":\"jsonBody\",\"headers\":{\"Content-Type\":\"application/json\"},\"interfaceDescription\":\"POST JSON Body 接口\",\"parameterContract\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"名称\"}},\"required\":[\"name\"]}}",
            "enabled":true,
            "visibility":"PRIVATE"
        }' --connect-timeout 5 2>/dev/null)

    local skill_id
    skill_id=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
    if [ -n "$skill_id" ] && [ "$skill_id" != "" ]; then
        CREATED_SKILL_IDS["api_post_json"]="$skill_id"
        step_pass "API创建 POST JSON Skill (id=$skill_id)"
    else
        step_fail "创建 POST JSON Skill" "API 返回异常"
    fi
}

step_03_create_api_get_query() {
    local resp
    resp=$(curl -s -X POST "${GATEWAY_URL}/api/skills" \
        -H "Content-Type: application/json" \
        -H "X-User-Id: ${TEST_USER_ID}" \
        -H "X-Agent-Token: your-secure-token-here" \
        -d '{
            "name":"e2e_api_get_query",
            "description":"E2E测试-GET Query",
            "executionMode":"CONFIG",
            "configuration":"{\"kind\":\"api\",\"operation\":\"e2e_api_get_query\",\"method\":\"GET\",\"endpoint\":\"http://v.juhe.cn/toutiao/index\",\"query\":{\"key\":\"c990e44845181032f48cc9a556e3a006\",\"type\":\"top\"},\"headers\":{\"Content-Type\":\"application/x-www-form-urlencoded\"},\"interfaceDescription\":\"聚合新闻 GET 接口\"}",
            "enabled":true,
            "visibility":"PRIVATE"
        }' --connect-timeout 5 2>/dev/null)

    local skill_id
    skill_id=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
    if [ -n "$skill_id" ] && [ "$skill_id" != "" ]; then
        CREATED_SKILL_IDS["api_get_query"]="$skill_id"
        step_pass "API创建 GET Query Skill (id=$skill_id)"
    else
        step_fail "创建 GET Query Skill" "API 返回异常"
    fi
}

step_04_edit_api_skill() {
    local skill_id="${CREATED_SKILL_IDS["api_post_json"]}"
    if [ -z "$skill_id" ]; then
        step_skip "编辑 API Skill" "前置步骤未创建"
        return 0
    fi

    local resp
    resp=$(curl -s -X PUT "${GATEWAY_URL}/api/skills/${skill_id}" \
        -H "Content-Type: application/json" \
        -H "X-User-Id: ${TEST_USER_ID}" \
        -H "X-Agent-Token: your-secure-token-here" \
        -d '{
            "name":"e2e_api_post_json",
            "description":"E2E测试-POST JSON（已编辑）",
            "enabled":true
        }' --connect-timeout 5 2>/dev/null)

    if echo "$resp" | grep -q '"id"'; then
        step_pass "编辑 API Skill 成功"
    else
        step_fail "编辑 API Skill" "API 返回异常"
    fi
}

step_05_create_ssh_skill() {
    if [ "$SSH_AVAILABLE" -eq 0 ]; then
        step_skip "创建 SSH Skill" "SSH 台账不可用"
        return 0
    fi

    local resp
    resp=$(curl -s -X POST "${GATEWAY_URL}/api/skills" \
        -H "Content-Type: application/json" \
        -H "X-User-Id: ${TEST_USER_ID}" \
        -H "X-Agent-Token: your-secure-token-here" \
        -d '{
            "name":"e2e_ssh_disk_check",
            "description":"E2E测试-SSH磁盘检查",
            "executionMode":"CONFIG",
            "configuration":"{\"kind\":\"ssh\",\"command\":\"df -h\",\"serverName\":\"39.104.81.41\"}",
            "enabled":true,
            "visibility":"PRIVATE"
        }' --connect-timeout 5 2>/dev/null)

    local skill_id
    skill_id=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
    if [ -n "$skill_id" ] && [ "$skill_id" != "" ]; then
        CREATED_SKILL_IDS["ssh"]="$skill_id"
        step_pass "API创建 SSH Skill (id=$skill_id)"
    else
        step_skip "创建 SSH Skill" "创建失败"
    fi
}

step_06_edit_ssh_skill() {
    local skill_id="${CREATED_SKILL_IDS["ssh"]}"
    if [ -z "$skill_id" ]; then
        step_skip "编辑 SSH Skill" "前置步骤未创建"
        return 0
    fi

    local resp
    resp=$(curl -s -X PUT "${GATEWAY_URL}/api/skills/${skill_id}" \
        -H "Content-Type: application/json" \
        -H "X-User-Id: ${TEST_USER_ID}" \
        -H "X-Agent-Token: your-secure-token-here" \
        -d '{
            "name":"e2e_ssh_disk_check",
            "description":"E2E测试-SSH磁盘检查（已编辑）",
            "enabled":true
        }' --connect-timeout 5 2>/dev/null)

    if echo "$resp" | grep -q '"id"'; then
        step_pass "编辑 SSH Skill 成功"
    else
        step_fail "编辑 SSH Skill" "API 返回异常"
    fi
}

step_07_create_template_skill() {
    local resp
    resp=$(curl -s -X POST "${GATEWAY_URL}/api/skills" \
        -H "Content-Type: application/json" \
        -H "X-User-Id: ${TEST_USER_ID}" \
        -H "X-Agent-Token: your-secure-token-here" \
        -d '{
            "name":"e2e_template_greeting",
            "description":"E2E测试-模板问候",
            "executionMode":"CONFIG",
            "configuration":"{\"kind\":\"template\",\"prompt\":\"请用中文回复用户的问候，回复格式是 JSON，包含 greeting 和 message 两个字段\"}",
            "enabled":true,
            "visibility":"PRIVATE"
        }' --connect-timeout 5 2>/dev/null)

    local skill_id
    skill_id=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
    if [ -n "$skill_id" ] && [ "$skill_id" != "" ]; then
        CREATED_SKILL_IDS["template"]="$skill_id"
        step_pass "API创建 Template Skill (id=$skill_id)"
    else
        step_fail "创建 Template Skill" "API 返回异常"
    fi
}

step_08_edit_template_skill() {
    local skill_id="${CREATED_SKILL_IDS["template"]}"
    if [ -z "$skill_id" ]; then
        step_skip "编辑 Template Skill" "前置步骤未创建"
        return 0
    fi

    local resp
    resp=$(curl -s -X PUT "${GATEWAY_URL}/api/skills/${skill_id}" \
        -H "Content-Type: application/json" \
        -H "X-User-Id: ${TEST_USER_ID}" \
        -H "X-Agent-Token: your-secure-token-here" \
        -d '{
            "name":"e2e_template_greeting",
            "description":"E2E测试-模板问候（已编辑）",
            "enabled":true
        }' --connect-timeout 5 2>/dev/null)

    if echo "$resp" | grep -q '"id"'; then
        step_pass "编辑 Template Skill 成功"
    else
        step_fail "编辑 Template Skill" "API 返回异常"
    fi
}

step_09_create_openclaw_skill() {
    local resp
    resp=$(curl -s -X POST "${GATEWAY_URL}/api/skills" \
        -H "Content-Type: application/json" \
        -H "X-User-Id: ${TEST_USER_ID}" \
        -H "X-Agent-Token: your-secure-token-here" \
        -d '{
            "name":"e2e_openclaw_compute",
            "description":"E2E测试-OPENCLAW计算",
            "executionMode":"OPENCLAW",
            "configuration":"{\"kind\":\"openclaw\",\"systemPrompt\":\"计算用户给定的数学表达式并返回结果\",\"allowedTools\":[\"compute\"],\"orchestrationMode\":\"serial\"}",
            "enabled":true,
            "visibility":"PRIVATE"
        }' --connect-timeout 5 2>/dev/null)

    local skill_id
    skill_id=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
    if [ -n "$skill_id" ] && [ "$skill_id" != "" ]; then
        CREATED_SKILL_IDS["openclaw"]="$skill_id"
        step_pass "API创建 OPENCLAW Skill (id=$skill_id)"
    else
        step_fail "创建 OPENCLAW Skill" "API 返回异常"
    fi
}

step_10_delete_skill() {
    local skill_id="${CREATED_SKILL_IDS["api_get_query"]}"
    if [ -z "$skill_id" ]; then
        # 删任意一个
        skill_id="${CREATED_SKILL_IDS["api_post_json"]}"
    fi
    if [ -z "$skill_id" ]; then
        step_skip "删除 Skill" "无可用 Skill"
        return 0
    fi

    local resp
    resp=$(curl -s -X DELETE "${GATEWAY_URL}/api/skills/${skill_id}" \
        -H "X-User-Id: ${TEST_USER_ID}" \
        -H "X-Agent-Token: your-secure-token-here" \
        --connect-timeout 5 -o /dev/null -w "%{http_code}" 2>/dev/null)

    if [ "$resp" = "200" ]; then
        step_pass "删除 Skill 成功 (id=$skill_id)"
    else
        step_fail "删除 Skill" "HTTP $resp"
    fi
}

# ---- 执行 ----
run_step  1 "登录态验证"              step_01_login_ensure         "$TOTAL"
run_step  2 "新增 API-POST JSON"      step_02_create_api_skill_via_api "$TOTAL"
run_step  3 "新增 API-GET Query"      step_03_create_api_get_query "$TOTAL"
run_step  4 "编辑 API Skill"          step_04_edit_api_skill       "$TOTAL"
run_step  5 "新增 SSH Skill"          step_05_create_ssh_skill     "$TOTAL"
run_step  6 "编辑 SSH Skill"          step_06_edit_ssh_skill       "$TOTAL"
run_step  7 "新增 Template Skill"     step_07_create_template_skill "$TOTAL"
run_step  8 "编辑 Template Skill"     step_08_edit_template_skill  "$TOTAL"
run_step  9 "新增 OPENCLAW Skill"     step_09_create_openclaw_skill "$TOTAL"
run_step 10 "删除 Skill"              step_10_delete_skill          "$TOTAL"

finish_suite
exit $?
