#!/data/data/com.termux/files/usr/bin/bash
set +e

TOKEN_ARG="${!#}"
if [[ "$TOKEN_ARG" == __MCP_CONTROL_TOKEN__=* ]]; then
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
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

send_callback() {
  local stage="$1"
  local stdout="$2"
  local stderr="$3"
  local rc="$4"
  local error_code="$5"
  local error_message="$6"
  [ -z "$TOKEN" ] && return 0
  /system/bin/am broadcast     -n com.re3ae6.mcpcontrol/.PluginResultsReceiver     -a com.re3ae6.mcpcontrol.RESULT     --es com.re3ae6.mcpcontrol.TOKEN "$TOKEN"     --es com.re3ae6.mcpcontrol.COMMAND "$COMMAND"     --es com.re3ae6.mcpcontrol.STAGE "$stage"     --es com.re3ae6.mcpcontrol.STDOUT "$stdout"     --es com.re3ae6.mcpcontrol.STDERR "$stderr"     --ei com.re3ae6.mcpcontrol.EXIT "$rc"     --ei com.re3ae6.mcpcontrol.ERROR_CODE "$error_code"     --es com.re3ae6.mcpcontrol.ERROR_MESSAGE "$error_message" >/dev/null 2>&1
}

send_callback "started" "" "" -1 0 ""

"$HOME/mcp-control/tools/mobile_control.sh" "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"
RC=$?

STDOUT="$(head -c 8000 "$TMP_DIR/stdout")"
STDERR="$(head -c 8000 "$TMP_DIR/stderr")"
send_callback "finished" "$STDOUT" "$STDERR" "$RC" 0 ""

cat "$TMP_DIR/stdout"
cat "$TMP_DIR/stderr" >&2
exit "$RC"
