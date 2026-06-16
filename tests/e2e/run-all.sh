#!/usr/bin/env bash
# ============================================================
# BXDC.bot E2E 测试编排器
# 按依赖顺序执行所有 Suite，生成汇总报告
#
# 执行顺序:
#   1. suite-01-login          (先验证认证)
#   2. suite-02-conversation   (对话基础功能)
#   3. suite-03-skill-crud     (通过表单创建全部类型 Skill)
#   4. suite-04-skill-generator(通过聊天生成全部类型 Skill)
#   5. suite-05-skill-execute  (执行已创建的 Skill)
#
# 用法:
#   bash tests/e2e/run-all.sh            # 全部运行
#   bash tests/e2e/run-all.sh 01 03      # 只运行 suite-01 和 suite-03
#   bash tests/e2e/run-all.sh --skip 04  # 跳过 suite-04
# ============================================================

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPORT_DIR="${SCRIPT_DIR}/reports"

# --------------- 汇总状态 ---------------
ALL_PASS=0
ALL_FAIL=0
ALL_SKIP=0
ALL_SUITES_TOTAL=0
ALL_SUITES_OK=0
ALL_SUITES_FAIL=0
SUITE_RESULTS=()

# --------------- 颜色 ---------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# 默认所有 suite
ALL_SUITES=("01" "02" "03" "04" "05")
SELECTED_SUITES=()
SKIP_SUITES=()

# 解析参数
for arg in "$@"; do
    case "$arg" in
        --skip)
            shift
            for s in "$@"; do SKIP_SUITES+=("$s"); done
            break
            ;;
        *)
            SELECTED_SUITES+=("$arg")
            ;;
    esac
done

# 确定最终要跑的 suites
if [ ${#SELECTED_SUITES[@]} -gt 0 ]; then
    SUITES_TO_RUN=("${SELECTED_SUITES[@]}")
else
    SUITES_TO_RUN=("${ALL_SUITES[@]}")
fi

# 过滤掉 skip 的
FINAL_SUITES=()
for s in "${SUITES_TO_RUN[@]}"; do
    skip_flag=0
    for skip in "${SKIP_SUITES[@]}"; do
        if [ "$s" = "$skip" ]; then
            skip_flag=1
            break
        fi
    done
    if [ "$skip_flag" -eq 0 ]; then
        FINAL_SUITES+=("$s")
    fi
done

# 打印 header
echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║${NC}   ${CYAN}BXDC.bot E2E 全量测试 v4${NC}                       ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}   $(date '+%Y-%m-%d %H:%M:%S')                              ${BLUE}║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════╝${NC}"
echo ""

# 环境预检
source "${SCRIPT_DIR}/common.sh"
start_browser
if ! run_health_checks; then
    echo -e "${RED}环境预检失败，终止测试${NC}"
    exit 1
fi

SUITE_FILES=()
for s in "${FINAL_SUITES[@]}"; do
    case "$s" in
        01) SUITE_FILES+=("${SCRIPT_DIR}/suite-01-login.sh") ;;
        02) SUITE_FILES+=("${SCRIPT_DIR}/suite-02-conversation.sh") ;;
        03) SUITE_FILES+=("${SCRIPT_DIR}/suite-03-skill-crud.sh") ;;
        04) SUITE_FILES+=("${SCRIPT_DIR}/suite-04-skill-generator.sh") ;;
        05) SUITE_FILES+=("${SCRIPT_DIR}/suite-05-skill-execute.sh") ;;
        *) echo -e "${YELLOW}⚠ 未知 suite: $s${NC}" ;;
    esac
done

# 执行所有 Suite
SUITE_EXIT_CODES=()
for suite_file in "${SUITE_FILES[@]}"; do
    if [ ! -f "$suite_file" ]; then
        echo -e "${RED}✗ 文件不存在: ${suite_file}${NC}"
        continue
    fi

    local suite_basename
    suite_basename=$(basename "$suite_file" .sh)

    echo ""
    echo -e "${CYAN}▶ 开始执行: ${suite_basename}${NC}"

    bash "$suite_file"
    local ec=$?
    SUITE_EXIT_CODES+=("${suite_basename}:${ec}")

    if [ "$ec" -eq 0 ]; then
        echo -e "${GREEN}✓ ${suite_basename} 通过${NC}"
        ALL_SUITES_OK=$((ALL_SUITES_OK + 1))
    else
        echo -e "${RED}✗ ${suite_basename} 有失败${NC}"
        ALL_SUITES_FAIL=$((ALL_SUITES_FAIL + 1))
    fi
    ALL_SUITES_TOTAL=$((ALL_SUITES_TOTAL + 1))
done

# 停止浏览器
stop_browser

# ============================================================
#  汇总报告
# ============================================================
echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║${NC}  全量测试汇总                                    ${BLUE}║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════╝${NC}"
echo ""
echo "  Suite 总数: ${ALL_SUITES_TOTAL}"
echo -e "  ${GREEN}通过: ${ALL_SUITES_OK}${NC}"
echo -e "  ${RED}失败: ${ALL_SUITES_FAIL}${NC}"
echo ""

for result in "${SUITE_EXIT_CODES[@]}"; do
    local name="${result%%:*}"
    local code="${result##*:}"
    if [ "$code" -eq 0 ]; then
        echo -e "  ${GREEN}✓${NC} $name"
    else
        echo -e "  ${RED}✗${NC} $name"
    fi
done

# 生成 Markdown 汇总报告
mkdir -p "$REPORT_DIR"
SUMMARY_FILE="${REPORT_DIR}/summary-$(date +%Y%m%d-%H%M%S).md"

cat > "$SUMMARY_FILE" << EOF
# BXDC.bot E2E 全量测试汇总

> **测试时间**: $(date '+%Y-%m-%d %H:%M:%S')
> **测试账号**: ${TEST_USER_ID} (${TEST_NICKNAME})

## Suite 结果

| Suite | 状态 |
|-------|------|
EOF

for result in "${SUITE_EXIT_CODES[@]}"; do
    local name="${result%%:*}"
    local code="${result##*:}"
    if [ "$code" -eq 0 ]; then
        echo "| $name | ✅ PASS |" >> "$SUMMARY_FILE"
    else
        echo "| $name | ❌ FAIL |" >> "$SUMMARY_FILE"
    fi
done

cat >> "$SUMMARY_FILE" << EOF

## 统计

| 指标 | 数据 |
|------|------|
| Suite 通过 | ${ALL_SUITES_OK} |
| Suite 失败 | ${ALL_SUITES_FAIL} |
| 通过率 | $(awk "BEGIN {printf \"%.0f\", ${ALL_SUITES_OK}*100/${ALL_SUITES_TOTAL}}")% |

## 详细报告

EOF

for result in "${SUITE_EXIT_CODES[@]}"; do
    local name="${result%%:*}"
    local latest_report
    latest_report=$(ls -t "${REPORT_DIR}/${name}"-*.md 2>/dev/null | head -1)
    if [ -n "$latest_report" ]; then
        echo "- [${name} 报告]($(basename "$latest_report"))" >> "$SUMMARY_FILE"
    fi
done

cat >> "$SUMMARY_FILE" << EOF

> 报告生成时间: $(date '+%Y-%m-%d %H:%M:%S')
EOF

echo ""
echo -e "${GREEN}📄 汇总报告: ${SUMMARY_FILE}${NC}"

if [ "$ALL_SUITES_FAIL" -gt 0 ]; then
    echo -e "${RED}有 Suite 未通过${NC}"
    exit 1
else
    echo -e "${GREEN}✅ 全部 Suite 通过!${NC}"
    exit 0
fi
