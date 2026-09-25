#!/data/data/com.termux/files/usr/bin/bash
set -u
REPO="$HOME/mcp-control"
cd "$REPO" || exit 1
case "${1:-}" in
  status) PYTHONPATH="$REPO" python3 -c 'import json; from core.connection import get_connection_status; print(json.dumps(get_connection_status()))' ;;
  policy) PYTHONPATH="$REPO" python3 -c 'import json; from core.policy import load_policy; print(json.dumps(load_policy()))' ;;
  set)
    [ -n "${2:-}" ] && [ -n "${3:-}" ] || exit 2
    case "$3" in deny|ask|allow) ;; *) exit 2 ;; esac
    PYTHONPATH="$REPO" python3 - "$2" "$3" <<'PY'
import sys
from core.trusted_control import set_capability
set_capability(sys.argv[1], sys.argv[2])
print('{"ok":true}')
PY
    ;;
  lock) PYTHONPATH="$REPO" python3 -c 'import json; from core.trusted_control import lock; lock(); print(json.dumps({"ok":true,"master_lock":true}))' ;;
  unlock) [ "${2:-}" = "UNLOCK" ] || exit 2; PYTHONPATH="$REPO" python3 -c 'import json; from core.policy import load_policy,save_policy,set_master_lock; from core.trusted_control import _audit; p=load_policy(); set_master_lock(p,False); save_policy(p); _audit("unlock","ALLOW","local_ui_confirmed"); print(json.dumps({"ok":true,"master_lock":false}))' ;;
  start|connect) exec env MCP_SKIP_GIT_PULL=1 "$HOME/po_recorder/tools/connect_mcp.sh" ;;
  restart) exec env MCP_FORCE_RESTART=1 MCP_SKIP_GIT_PULL=1 "$HOME/po_recorder/tools/connect_mcp.sh" ;;
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
  *) exit 2 ;;
esac
