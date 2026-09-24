#!/usr/bin/env python3
"""Launch termux-native-mcp behind the MCP Control policy gate."""
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from core.enforcer import check, record, request_approval, consume_approval
from core.command_guard import authorize_command
from termux_mcp import mcp_core, mcp_server


def _capability(name: str) -> str:
    exact = {
        "ls": "files.repo",
        "read": "files.repo",
        "search": "files.repo",
        "context": "files.repo",
        "history": "files.repo",
        "changes_list": "files.repo",
        "write": "files.repo_tools",
        "mkdir": "files.repo_tools",
        "delete": "dangerous.delete",
        "backup": "files.repo_reports",
        "restore": "files.repo_reports",
        "undo": "files.repo_tools",
        "history_save": "files.repo_tools",
        "history_clear": "files.repo_tools",
        "recipe_save": "files.repo_tools",
        "run": "terminal.bash",
        "cancel": "terminal.bash",
        "session_start": "terminal.background",
        "session_run": "terminal.bash",
        "session_poll": "terminal.bash",
        "session_list": "terminal.bash",
        "session_kill": "terminal.kill",
        "terminal_open": "terminal.bash",
        "terminal_run": "terminal.bash",
        "terminal_send": "terminal.bash",
        "terminal_read": "terminal.bash",
        "terminal_list": "terminal.bash",
        "terminal_close": "terminal.bash",
        "system_info": "terminal.process",
        "health": "terminal.process",
        "battery": "device.other",
        "process_list": "terminal.process",
        "cron_list": "terminal.process",
        "process_kill": "dangerous.kill",
        "cron_add": "terminal.background",
        "cron_remove": "dangerous.kill",
        "git_pr": "git.diff",
        "diff": "git.diff",
        "open_url": "network.internet",
        "download": "network.internet",
        "public_ip": "network.internet",
        "weather": "network.internet",
        "speedtest": "network.internet",
        "qrcode": "network.internet",
        "cloud_sync": "network.internet",
        "location": "device.location",
        "camera_photo": "device.camera",
        "screenshot": "device.camera",
        "image_process": "device.camera",
        "text_extract": "device.camera",
        "sms_send": "device.sms",
        "sms_inbox": "device.sms",
        "clipboard_get": "device.other",
        "clipboard_set": "device.other",
        "notify": "device.notifications",
        "toast": "device.notifications",
        "tts_speak": "device.other",
        "share": "device.other",
        "smart_install": "dangerous.install",
    }
    return exact.get(name, "dangerous.outside_allowlist")

_original = mcp_core.call_tool


def guarded_call(session, name, params, on_progress=None):
    cap = _capability(name)
    if name == "run" and isinstance(params, dict):
        command = params.get("cmd", params.get("command", ""))
        ok, reason = authorize_command(command)
        if not ok:
            record("dangerous.outside_allowlist", f"mcp.tools/call:{name}", "DENY_COMMAND")
            return {"content": [{"type": "text", "text": f"MCP CONTROL: command denied: {reason}"}], "isError": True}
    d = check(cap)
    if not d.allowed:
        if d.requires_approval:
            approval_id = params.get("approval_id") if isinstance(params, dict) else None

            if approval_id:
                clean_params = dict(params)
                clean_params.pop("approval_id", None)
                try:
                    consume_approval(
                        approval_id,
                        cap,
                        name,
                        clean_params,
                    )
                except PermissionError as e:
                    record(
                        cap,
                        f"mcp.tools/call:{name}",
                        f"ASK_DENY:{e}",
                    )
                    return {
                        "content": [{
                            "type": "text",
                            "text": f"MCP CONTROL: approval denied: {e}",
                        }],
                        "isError": True,
                    }

                record(
                    cap,
                    f"mcp.tools/call:{name}",
                    "APPROVED_ALLOW",
                )
                return _original(
                    session,
                    name,
                    clean_params,
                    on_progress=on_progress,
                )

            approval = request_approval(cap, name, params)
            record(
                cap,
                f"mcp.tools/call:{name}",
                "ASK",
            )
            return {
                "content": [{
                    "type": "text",
                    "text": (
                        f"MCP CONTROL: approval required for {cap}; "
                        f"approval_id={approval['approval_id']}; "
                        f"expires_at={approval['expires_at']}"
                    ),
                }],
                "isError": True,
            }

        record(
            cap,
            f"mcp.tools/call:{name}",
            "DENY",
        )
        return {
            "content": [{
                "type": "text",
                "text": (
                    f"MCP CONTROL: access denied ({cap}); "
                    "master lock/policy is active"
                ),
            }],
            "isError": True,
        }

    record(cap, f"mcp.tools/call:{name}", "ALLOW")
    return _original(session, name, params, on_progress=on_progress)


mcp_core.call_tool = guarded_call

if __name__ == "__main__":
    mcp_server.run_http(host="127.0.0.1", port=8081)
