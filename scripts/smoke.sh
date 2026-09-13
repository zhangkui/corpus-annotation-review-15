#!/usr/bin/env bash
# ============================================================================
# 冒烟测试：验证五条核心业务规则（需要 python3 解析 JSON）
# 用法：./scripts/smoke.sh [API_BASE]   默认 http://localhost:9000
# ============================================================================
set -euo pipefail

BASE="${1:-http://localhost:9000}"
PASS=0; FAIL=0
RUN_ID="$(date +%s)"

check() { # <名称> <期望> <实际>
  if [ "$2" = "$3" ]; then
    echo "  PASS: $1"; PASS=$((PASS + 1))
  else
    echo "  FAIL: $1（期望 $2，实际 $3）"; FAIL=$((FAIL + 1))
  fi
}

json_get() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

echo "== 0. 健康检查 =="
curl -sf "$BASE/api/health" && echo

echo "== 规则1：相同片段同一标签范围的重复标注合并展示 =="
resp=$(curl -sf "$BASE/api/segments/1/annotations/merged")
cnt=$(echo "$resp" | json_get "[m['count'] for m in d if m['startOffset']==0][0]")
check "片段1 [0,2)「人名」合并计数=2（alice+bob）" "2" "$cnt"

echo "== 规则2：标签层级不能形成环 =="
code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE/api/tags/1/parent" \
  -H 'Content-Type: application/json' -d '{"parentId": 2}')
check "把「实体」挂到其子节点「人名」下 → 409" "409" "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE/api/tags/2/parent" \
  -H 'Content-Type: application/json' -d '{"parentId": 2}')
check "标签作为自己的父节点 → 409" "409" "$code"

echo "== 规则3：仲裁只能引用已有标注版本并生成新结论 =="
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/arbitrations" \
  -H 'Content-Type: application/json' \
  -d '{"segmentId":1,"arbitratorId":5,"sourceVersionIds":[99999],"conclusions":[{"tagId":2,"startOffset":0,"endOffset":2}]}')
check "引用不存在的版本 → 422" "422" "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/arbitrations" \
  -H 'Content-Type: application/json' \
  -d '{"segmentId":1,"arbitratorId":5,"sourceVersionIds":[1,2],"comment":"冒烟测试仲裁","conclusions":[{"tagId":2,"startOffset":0,"endOffset":2,"note":"确认"}]}')
check "引用已有版本 [1,2] → 201 并生成新结论" "201" "$code"

echo "== 规则4：任务分配避免同一用户连续获得同一批次 =="
uname="smoke_$RUN_ID"
uid=$(curl -sf -X POST "$BASE/api/users" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$uname\",\"displayName\":\"冒烟\",\"role\":\"annotator\"}" | json_get "d['id']")
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/tasks/1/assign" \
  -H 'Content-Type: application/json' -d "{\"userId\": $uid}")
check "新用户首次分配（批次1）→ 201" "201" "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/tasks/2/assign" \
  -H 'Content-Type: application/json' -d "{\"userId\": $uid}")
check "同一用户连续获得同一批次（任务2同属批次1）→ 409" "409" "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/tasks/3/assign" \
  -H 'Content-Type: application/json' -d "{\"userId\": $uid}")
check "跨批次分配（任务3属批次2）→ 201" "201" "$code"

echo "== 规则5：导入校验字符偏移与语言代码 =="
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/import/segments" \
  -H 'Content-Type: application/json' \
  -d '{"corpus":"smoke","segments":[{"languageCode":"xx-invalid","content":"abc","annotations":[]}]}')
check "非法语言代码 → 422" "422" "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/import/segments" \
  -H 'Content-Type: application/json' \
  -d '{"corpus":"smoke","segments":[{"languageCode":"zh","content":"短文本","annotations":[{"tag":"实体","startOffset":0,"endOffset":99}]}]}')
check "字符偏移越界 → 422" "422" "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/import/segments" \
  -H 'Content-Type: application/json' \
  -d "{\"corpus\":\"smoke\",\"segments\":[{\"externalId\":\"smoke-$RUN_ID\",\"languageCode\":\"zh-Hans\",\"content\":\"合法文本。\",\"annotations\":[{\"tag\":\"实体\",\"startOffset\":0,\"endOffset\":2}]}]}")
check "合法导入 → 200" "200" "$code"

echo
echo "==============================================="
echo "结果：通过 $PASS 项，失败 $FAIL 项"
[ "$FAIL" = "0" ]
