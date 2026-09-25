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
    exit)
      # Explicit shutdown path for the notification EXIT action.
      # Stop the watchdog first so it cannot immediately recreate the MCP stack.
      pkill -f "$HOME/po_recorder/tools/mcp_watchdog.sh" >/dev/null 2>&1 || true
      pkill -f "mcp_watchdog.sh" >/dev/null 2>&1 || true

      # Stop tunnel and guarded MCP server using exact command patterns.
      pkill -f "$HOME/tunnel-client-install/tunnel-client run .*po-termux" >/dev/null 2>&1 || true
      pkill -f "mcp-control/runtime/guarded_server.py" >/dev/null 2>&1 || true
      pkill -f "termux-native-mcp.*--host 127.0.0.1.*--port 8081" >/dev/null 2>&1 || true

      # The proxy is an anonymous Python listener; close only the process
      # owning TCP port 18081 rather than unrelated Python processes.
      python3 - <<'PY'
import os
import signal
from pathlib import Path

PORT_HEX = f"{18081:04X}"
inodes = set()
for proc in ("/proc/net/tcp", "/proc/net/tcp6"):
    try:
        for line in Path(proc).read_text().splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 10:
                local = parts[1]
                state = parts[3]
                inode = parts[9]
                if state == "0A" and local.rsplit(":", 1)[-1].upper() == PORT_HEX:
                    inodes.add(inode)
    except Exception:
        pass

if inodes:
    wanted = {f"socket:[{inode}]" for inode in inodes}
    for pid_dir in Path("/proc").glob("[0-9]*"):
        try:
            pid = int(pid_dir.name)
            for fd in (pid_dir / "fd").iterdir():
                try:
                    if os.readlink(fd) in wanted:
                        os.kill(pid, signal.SIGTERM)
                        break
                except Exception:
                    pass
        except Exception:
            pass
PY

      rm -f "$HOME/po_recorder/tmp/mcp_tmp" >/dev/null 2>&1 || true
      printf '%s\n' '{"ok":true,"mcp_stopped":true,"proxy_stopped":true,"tunnel_stopped":true}'
      ;;
    unlock)
      [ "${2:-}" = "UNLOCK" ] || return 2
      PYTHONPATH="$REPO" python3 -c 'import json; from core.policy import load_policy,save_policy,set_master_lock; from core.trusted_control import _audit; p=load_policy(); set_master_lock(p,False); save_policy(p); _audit("unlock","ALLOW","local_ui_confirmed"); print(json.dumps({"ok":true,"master_lock":false}))'
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
