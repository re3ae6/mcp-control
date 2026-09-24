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
        "ls": "files.list",
        "read": "files.read",
        "search": "files.search",
        "context": "files.context",
        "history": "files.history",
        "changes_list": "files.changes",
        "write": "files.write",
        "mkdir": "files.mkdir",
        "run": "terminal.run",
        "cancel": "terminal.cancel",
        "session_start": "terminal.background",
        "session_run": "terminal.run",
        "session_poll": "terminal.poll",
        "session_list": "terminal.list",
        "session_kill": "terminal.kill",
        "terminal_open": "terminal.run",
        "terminal_run": "terminal.run",
        "terminal_send": "terminal.run",
        "terminal_read": "terminal.read",
        "terminal_list": "terminal.list",
        "terminal_close": "terminal.cancel",
        "location": "device.location",
        "camera_photo": "device.camera",
        "screenshot": "device.camera",
        "image_process": "device.camera",
        "text_extract": "device.camera",
        "sms_send": "device.sms",
        "sms_inbox": "device.sms",
        "clipboard_get": "device.clipboard",
        "clipboard_set": "device.clipboard",
        "notify": "device.notifications",
        "toast": "device.notifications",
        "tts_speak": "device.tts",
        "share": "device.share",
        "open_url": "network.internet",
        "download": "network.internet",
        "public_ip": "network.internet",
        "weather": "network.internet",
        "speedtest": "network.internet",
        "qrcode": "network.internet",
        "cloud_sync": "network.internet",
        "delete": "dangerous.delete",
        "process_kill": "dangerous.kill",
        "smart_install": "dangerous.install",
        "system_info": "terminal.process",
        "health": "terminal.process",
        "process_list": "terminal.process",
        "cron_list": "terminal.process",
        "cron_add": "terminal.background",
        "cron_remove": "dangerous.kill",
        "git_pr": "git.diff",
        "diff": "git.diff",
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
