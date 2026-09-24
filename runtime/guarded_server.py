#!/usr/bin/env python3
"""Launch termux-native-mcp behind the MCP Control policy gate."""
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from core.enforcer import check, record
from termux_mcp import mcp_core, mcp_server


def _capability(name: str) -> str:
    if name in {"run", "cancel", "session_start", "session_run", "session_poll", "session_list", "session_kill", "terminal_open", "terminal_run", "terminal_send", "terminal_read", "terminal_list", "terminal_close"}:
        return "terminal.bash"
    if name in {"ls", "read", "search", "context", "history", "changes_list"}:
        return "files.repo"
    if name in {"write", "mkdir", "delete", "backup", "restore", "undo", "history_save", "history_clear", "recipe_save"}:
        return "files.repo"
    if name in {"location"}: return "device.location"
    if name in {"camera_photo", "screenshot", "image_process", "text_extract"}: return "device.camera"
    if name in {"sms_send", "sms_inbox"}: return "device.sms"
    if name in {"clipboard_get", "clipboard_set"}: return "device.other"
    if name in {"notify", "toast"}: return "device.notifications"
    if name in {"system_info", "health", "battery", "process_list", "cron_list"}: return "terminal.process"
    if name in {"process_kill", "cron_add", "cron_remove"}: return "dangerous.kill"
    if name in {"smart_install"}: return "dangerous.install"
    if name in {"git_pr", "diff"}: return "git.diff"
    if name in {"open_url", "download", "public_ip", "weather", "speedtest", "qrcode", "cloud_sync"}: return "network.internet"
    return "dangerous.outside_allowlist"


_original = mcp_core.call_tool


def guarded_call(session, name, params, on_progress=None):
    cap = _capability(name)
    d = check(cap)
    if not d.allowed:
        result = "ASK" if d.requires_approval else "DENY"
        record(cap, f"mcp.tools/call:{name}", result)
        if d.requires_approval:
            return {"text": f"MCP CONTROL: approval required for {cap} (tool={name})", "is_error": True}
        return {"text": f"MCP CONTROL: access denied ({cap}); master lock/policy is active", "is_error": True}
    record(cap, f"mcp.tools/call:{name}", "ALLOW")
    return _original(session, name, params, on_progress=on_progress)


mcp_core.call_tool = guarded_call

if __name__ == "__main__":
    mcp_server.run_http(host="127.0.0.1", port=8081)
