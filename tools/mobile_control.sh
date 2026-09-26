#!/data/data/com.termux/files/usr/bin/bash
set +e

REPO="$HOME/mcp-control"
cd "$REPO" || exit 1

TOKEN_ARG="${!#}"
if [[ "${TOKEN_ARG:-}" == __MCP_CONTROL_TOKEN__=* ]]; then
  TOKEN="${TOKEN_ARG#__MCP_CONTROL_TOKEN__=}"
  if [ "$#" -gt 1 ]; then
    set -- "${@:1:$(($#-1))}"
  else
    set --
  fi
else
  TOKEN=""
fi

COMMAND="${1:-}"
TMP_DIR=""
if [ -n "$TOKEN" ]; then
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
fi

send_callback() {
  local stage="$1"
  local stdout="$2"
  local stderr="$3"
  local rc="$4"
  local error_code="$5"
  local error_message="$6"
  [ -z "$TOKEN" ] && return 0
  /system/bin/am broadcast \
    -n com.re3ae6.mcpcontrol/.PluginResultsReceiver \
    -a com.re3ae6.mcpcontrol.RESULT \
    --es com.re3ae6.mcpcontrol.TOKEN "$TOKEN" \
    --es com.re3ae6.mcpcontrol.COMMAND "$COMMAND" \
    --es com.re3ae6.mcpcontrol.STAGE "$stage" \
    --es com.re3ae6.mcpcontrol.STDOUT "$stdout" \
    --es com.re3ae6.mcpcontrol.STDERR "$stderr" \
    --ei com.re3ae6.mcpcontrol.EXIT "$rc" \
    --ei com.re3ae6.mcpcontrol.ERROR_CODE "$error_code" \
    --es com.re3ae6.mcpcontrol.ERROR_MESSAGE "$error_message" >/dev/null 2>&1
}

run_control() {
  case "${1:-}" in
    status)
      PYTHONPATH="$REPO" python3 -c 'import json; from core.connection import get_connection_status; print(json.dumps(get_connection_status()))'
      ;;
    policy)
      PYTHONPATH="$REPO" python3 -c 'import json; from core.policy import load_policy; print(json.dumps(load_policy()))'
      ;;
    add_path)
      [ -n "${2:-}" ] || return 2
      PYTHONPATH="$REPO" python3 - "$2" <<'PY'
import sys, json
from core.trusted_control import add_custom_path
add_custom_path(sys.argv[1])
print(json.dumps({"ok":True,"path":sys.argv[1]}))
PY
      ;;
    remove_path)
      [ -n "${2:-}" ] || return 2
      PYTHONPATH="$REPO" python3 - "$2" <<'PY'
import sys, json
from core.trusted_control import remove_custom_path
remove_custom_path(sys.argv[1])
print(json.dumps({"ok":True,"path":sys.argv[1]}))
PY
      ;;
    set)
      [ -n "${2:-}" ] && [ -n "${3:-}" ] || return 2
      case "$3" in deny|ask|allow) ;; *) return 2 ;; esac
      PYTHONPATH="$REPO" python3 - "$2" "$3" <<'PY'
import sys
from core.trusted_control import set_capability
set_capability(sys.argv[1], sys.argv[2])
print('{"ok":true}')
PY
      ;;
    lock)
      PYTHONPATH="$REPO" python3 -c 'import json; from core.trusted_control import lock; lock(); print(json.dumps({"ok":true,"master_lock":true}))'
      ;;
    unlock)
      [ "${2:-}" = "UNLOCK" ] || return 2
      PYTHONPATH="$REPO" python3 - "UNLOCK" <<'PY'
import sys
import json
from core.trusted_control import unlock
try:
    result = unlock(sys.argv[1], local_ui=True)
    print(json.dumps({"ok": True, "master_lock": False}))
except PermissionError as e:
    print(json.dumps({"ok": False, "error": str(e)}))
    raise
PY
      ;;
    start|connect)
      env MCP_SKIP_GIT_PULL=1 "$HOME/po_recorder/tools/connect_mcp.sh"
      ;;
    restart)
      env MCP_FORCE_RESTART=1 MCP_SKIP_GIT_PULL=1 "$HOME/po_recorder/tools/connect_mcp.sh"
      ;;
    approvals)
      PYTHONPATH="$REPO" python3 - <<'PY'
import json
from pathlib import Path
from datetime import datetime, timezone
p=Path.home()/".config"/"mcp-control"/"approvals.json"
if not p.exists():
    print(json.dumps({"approvals": []}))
    raise SystemExit
try:
    data=json.loads(p.read_text(encoding="utf-8"))
except Exception:
    print(json.dumps({"approvals": []}))
    raise SystemExit
now=datetime.now(timezone.utc)
out=[]
for item in data.get("approvals",[]):
    if item.get("status")=="pending" and not item.get("consumed",False):
        try:
            if now < datetime.fromisoformat(item["expires_at"]): out.append(item)
        except Exception: pass
print(json.dumps({"approvals": out}, ensure_ascii=False))
PY
      ;;
    approve)
      [ -n "${2:-}" ] || return 2
      PYTHONPATH="$REPO" python3 - "$2" <<'PY'
import sys
import json
from core.approval import approve
print(json.dumps(approve(sys.argv[1]), ensure_ascii=False))
PY
      ;;
    audit)
      PYTHONPATH="$REPO" python3 - <<'PY'
import json
from pathlib import Path
root=Path.home()/'.config'/'mcp-control'
for name in ('control_audit.jsonl','audit.jsonl'):
    p=root/name
    if p.exists():
        print(json.dumps({"file":name,"lines":p.read_text(encoding="utf-8").splitlines()[-30:]}))
PY
      ;;
    *)
      return 2
      ;;
  esac
}

if [ -n "$TOKEN" ]; then
  send_callback "started" "" "" -1 0 ""
  run_control "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"
  RC=$?
  STDOUT="$(head -c 8000 "$TMP_DIR/stdout")"
  STDERR="$(head -c 8000 "$TMP_DIR/stderr")"
  send_callback "finished" "$STDOUT" "$STDERR" "$RC" 0 ""
  cat "$TMP_DIR/stdout"
  cat "$TMP_DIR/stderr" >&2
  exit "$RC"
else
  run_control "$@"
  exit $?
fi