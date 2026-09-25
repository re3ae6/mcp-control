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


_FILE_TOOLS = {
    "ls": "files.list",
    "read": "files.read",
    "search": "files.search",
    "context": "files.context",
    "history": "files.history",
    "changes_list": "files.changes",
    "write": "files.write",
    "mkdir": "files.mkdir",
}

def _request_path(params):
    raw = params.get("path", ".") if isinstance(params, dict) else "."
    if not isinstance(raw, str) or not raw.strip():
        raw = "."
    return Path(raw).expanduser().resolve()

def _file_decisions(name, params):
    """Return both the tool capability and the resolved filesystem scope."""
    capability = _FILE_TOOLS.get(name)
    if not capability:
        return []
    return [capability, _file_scope(params)]

def _file_scope(params):
    path = _request_path(params)
    home = Path.home()
    scoped = ((home / "po_recorder" / "data", "files.repo_data"), (home / "po_recorder" / "tools", "files.repo_tools"), (home / "po_recorder" / "reports", "files.repo_reports"), (home / "po_recorder" / "tmp", "files.repo_tmp"), (home / "tunnel-client-install", "files.tunnel_install"), (Path("/sdcard"), "files.shared_storage"))
    for root, capability in scoped:
        root = root.resolve()
        if path == root or root in path.parents:
            return capability
    roots = ((home / "po_recorder", "files.repo"), (home / "mcp-control", "files.control"), (home, "files.home"))
    for root, capability in roots:
        root = root.resolve()
        if path == root or root in path.parents:
            return capability
    return "dangerous.outside_allowlist"

# Preserve the real MCP implementation before installing the policy wrapper.
_original = mcp_core.call_tool

def guarded_call(session, name, params, on_progress=None):
    cap = _capability(name)
    decisions = [cap]
    # decisions already built above
    if name in {"run", "terminal_run", "terminal_send", "session_run"} and isinstance(params, dict):
        command = params.get("cmd", params.get("command", "")).strip()
        if command.startswith("git pull"): decisions.append("git.pull")
        elif command.startswith("git status"): decisions.append("git.status")
        elif command.startswith("git diff"): decisions.append("git.diff")
        elif command.startswith("git add"): decisions.append("git.add")
        elif command.startswith("git commit"): decisions.append("git.commit")
        elif command.startswith("git push"): decisions.append("git.push")
        elif command.startswith("git switch") or command.startswith("git checkout") or command.startswith("git branch"): decisions.append("git.branch")
    extra = {"run":"mcp.execute", "terminal_run":"mcp.execute", "terminal_send":"mcp.execute", "session_run":"mcp.execute", "session_start":"mcp.long_running", "session_poll":"mcp.read_output", "terminal_read":"mcp.read_output", "session_list":"mcp.process", "terminal_list":"mcp.process", "session_kill":"mcp.process", "terminal_close":"mcp.long_running", "write":"mcp.modify", "mkdir":"mcp.create", "delete":"mcp.delete"}.get(name)
    if extra and extra not in decisions: decisions.append(extra)
    if name in {"run", "terminal_run", "terminal_send", "session_run"} and isinstance(params, dict):
        command = params.get("cmd", params.get("command", ""))
        ok, reason = authorize_command(command)
        if not ok:
            record("dangerous.outside_allowlist", f"mcp.tools/call:{name}", "DENY_COMMAND")
            return {"content": [{"type": "text", "text": f"MCP CONTROL: command denied: {reason}"}], "isError": True}

    # decisions already built above
    for required_cap in _file_decisions(name, params):
        if required_cap not in decisions:
            decisions.append(required_cap)

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
