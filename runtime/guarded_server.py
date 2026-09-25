#!/usr/bin/env python3
"""Launch termux-native-mcp behind the MCP Control policy gate."""
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from core.enforcer import check, record, request_approval, consume_approval
from core.command_guard import authorize_command
from core.capability_map import capability_for_tool, secondary_for_tool, git_capability_for_command, file_capabilities_for_path
from termux_mcp import mcp_core, mcp_server


def _request_path(params):
    raw = params.get("path", ".") if isinstance(params, dict) else "."
    return Path(raw).expanduser().resolve() if isinstance(raw, str) and raw.strip() else Path(".").resolve()

def _file_decisions(name, params):
    return file_capabilities_for_path(name, _request_path(params))

_original = mcp_core.call_tool

def guarded_call(session, name, params, on_progress=None):
    decisions = [capability_for_tool(name)]
    if name in {"run", "terminal_run", "terminal_send", "session_run"} and isinstance(params, dict):
        command = params.get("cmd", params.get("command", "")).strip()
        git_cap = git_capability_for_command(command)
        if git_cap and git_cap not in decisions: decisions.append(git_cap)
    for extra in secondary_for_tool(name):
        if extra not in decisions: decisions.append(extra)

    if name in {"run", "terminal_run", "terminal_send", "session_run"} and isinstance(params, dict):
        command = params.get("cmd", params.get("command", ""))
        ok, reason = authorize_command(command)
        if not ok:
            record("dangerous.outside_allowlist", f"mcp.tools/call:{name}", "DENY_COMMAND")
            return {"content": [{"type": "text", "text": f"MCP CONTROL: command denied: {reason}"}], "isError": True}

    # decisions already built above
    for required_cap in _file_decisions(name, params):
        if required_cap not in decisions: decisions.append(required_cap)

    for required_cap in decisions:
        d = check(required_cap)
        if d.allowed:
            continue

        if d.requires_approval:
            approval_id = params.get("approval_id") if isinstance(params, dict) else None
            if approval_id:
                clean_params = dict(params)
                clean_params.pop("approval_id", None)
                try:
                    consume_approval(approval_id, required_cap, name, clean_params)
                except PermissionError as e:
                    record(required_cap, f"mcp.tools/call:{name}", f"ASK_DENY:{e}")
                    return {
                        "content": [{"type": "text", "text": f"MCP CONTROL: approval denied: {e}"}],
                        "isError": True,
                    }
                record(required_cap, f"mcp.tools/call:{name}", "APPROVED_ALLOW")
                continue

            approval = request_approval(required_cap, name, params)
            record(required_cap, f"mcp.tools/call:{name}", "ASK")
            return {
                "content": [{
                    "type": "text",
                    "text": (
                        f"MCP CONTROL: approval required for {required_cap}; "
                        f"approval_id={approval['approval_id']}; "
                        f"expires_at={approval['expires_at']}"
                    ),
                }],
                "isError": True,
            }

        record(required_cap, f"mcp.tools/call:{name}", "DENY")
        return {
            "content": [{
                "type": "text",
                "text": f"MCP CONTROL: access denied ({required_cap}); policy is active",
            }],
            "isError": True,
        }

    for required_cap in decisions:
        record(required_cap, f"mcp.tools/call:{name}", "ALLOW")
    return _original(session, name, params, on_progress=on_progress)


mcp_core.call_tool = guarded_call

if __name__ == "__main__":
    mcp_server.run_http(host="127.0.0.1", port=8081)
