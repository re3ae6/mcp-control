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

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

"$HOME/mcp-control/tools/mobile_control.sh" "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"
RC=$?

if [ -n "$TOKEN" ]; then
  STDOUT="$(head -c 8000 "$TMP_DIR/stdout")"
  STDERR="$(head -c 8000 "$TMP_DIR/stderr")"
  /system/bin/am broadcast \
    -n com.re3ae6.mcpcontrol/.PluginResultsReceiver \
    -a com.re3ae6.mcpcontrol.RESULT \
    --es com.re3ae6.mcpcontrol.TOKEN "$TOKEN" \
    --es com.re3ae6.mcpcontrol.COMMAND "${1:-}" \
    --es com.re3ae6.mcpcontrol.STDOUT "$STDOUT" \
    --es com.re3ae6.mcpcontrol.STDERR "$STDERR" \
    --ei com.re3ae6.mcpcontrol.EXIT "$RC" \
    --ei com.re3ae6.mcpcontrol.ERROR_CODE 0 \
    --es com.re3ae6.mcpcontrol.ERROR_MESSAGE "" >/dev/null 2>&1
fi

cat "$TMP_DIR/stdout"
cat "$TMP_DIR/stderr" >&2
exit "$RC"
