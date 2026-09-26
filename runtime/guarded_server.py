#!/usr/bin/env python3
"""Launch termux-native-mcp behind the MCP Control policy gate."""
from __future__ import annotations
import sys
import base64
import mimetypes
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from core.enforcer import check, record, request_approval_bundle, consume_approval_bundle
from core.command_guard import authorize_command
from core.capability_map import (
    capability_for_tool,
    secondary_for_tool,
    git_capability_for_command,
    file_capabilities_for_path,
    file_capabilities_for_params,
    file_scope,
)
from termux_mcp import mcp_core, mcp_server


def _request_path(params):
    raw = params.get("path", ".") if isinstance(params, dict) else "."
    return Path(raw).expanduser().resolve() if isinstance(raw, str) and raw.strip() else Path(".").resolve()


def _file_decisions(name, params):
    decisions = file_capabilities_for_path(name, _request_path(params))
    decisions.extend(file_capabilities_for_params(name, params))
    return decisions


_original = mcp_core.call_tool
_original_tool_list = mcp_core.tool_list

_IMAGE_LIST_TOOL = {
    "name": "image_list",
    "description": "List image files currently present in a selected folder.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": "Selected image folder path"}
        },
        "required": ["path"]
    },
}

_IMAGE_READ_TOOL = {
    "name": "image_read",
    "description": "Read an image file and return the actual image as MCP ImageContent.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "input": {"type": "string", "description": "Image file path"}
        },
        "required": ["input"]
    },
}

def _image_list_result(params):
    raw = params.get("path", "") if isinstance(params, dict) else ""
    if not isinstance(raw, str) or not raw.strip():
        return {"content": [{"type": "text", "text": "MCP CONTROL: missing image folder path"}], "isError": True}
    folder = Path(raw).expanduser().resolve()
    if not folder.is_dir():
        return {"content": [{"type": "text", "text": f"MCP CONTROL: image folder not found: {folder}"}], "isError": True}
    exts = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif"}
    images = []
    for item in sorted(folder.iterdir(), key=lambda p: p.stat().st_mtime, reverse=True):
        try:
            if item.is_file() and item.suffix.lower() in exts:
                images.append({
                    "name": item.name,
                    "path": str(item),
                    "size": item.stat().st_size,
                    "modified": item.stat().st_mtime,
                })
        except OSError:
            continue
    return {"content": [{"type": "text", "text": json.dumps(
        {"ok": True, "folder": str(folder), "images": images}, ensure_ascii=False
    )}]}

def _image_read_result(params):
    raw = params.get("input", "") if isinstance(params, dict) else ""
    if not isinstance(raw, str) or not raw.strip():
        return {"content": [{"type": "text", "text": "MCP CONTROL: missing image input"}], "isError": True}
    path = Path(raw).expanduser().resolve()
    if not path.is_file():
        return {"content": [{"type": "text", "text": f"MCP CONTROL: image not found: {path}"}], "isError": True}
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    if not mime.startswith("image/"):
        return {"content": [{"type": "text", "text": f"MCP CONTROL: not an image: {path}"}], "isError": True}
    data = base64.b64encode(path.read_bytes()).decode("ascii")
    return {"content": [{"type": "image", "data": data, "mimeType": mime}, {"type": "text", "text": f"Image: {path.name}"}]}

def guarded_tool_list():
    result = _original_tool_list()
    tools = list(result.get("tools", []))
    if not any(t.get("name") == "image_list" for t in tools):
        tools.append(_IMAGE_LIST_TOOL)
    if not any(t.get("name") == "image_read" for t in tools):
        tools.append(_IMAGE_READ_TOOL)
    return {"tools": tools}



def guarded_call(session, name, params, on_progress=None):
    if name == "image_list":
        raw = params.get("path", "") if isinstance(params, dict) else ""
        decisions = ["files.list", file_scope(Path(raw).expanduser().resolve())]
        for required_cap in decisions:
            d = check(required_cap)
            if not d.allowed:
                record(required_cap, f"mcp.tools/call:{name}", "DENY")
                return {"content": [{"type": "text", "text": f"MCP CONTROL: access denied ({required_cap}); policy is active"}], "isError": True}
        for required_cap in decisions:
            record(required_cap, f"mcp.tools/call:{name}", "ALLOW")
        return _image_list_result(params)

    if name == "image_read":
        raw = params.get("input", "") if isinstance(params, dict) else ""
        decisions = ["files.read", file_scope(Path(raw).expanduser().resolve())]
        for required_cap in decisions:
            d = check(required_cap)
            if not d.allowed:
                record(required_cap, f"mcp.tools/call:{name}", "DENY")
                return {"content": [{"type": "text", "text": f"MCP CONTROL: access denied ({required_cap}); policy is active"}], "isError": True}
        for required_cap in decisions:
            record(required_cap, f"mcp.tools/call:{name}", "ALLOW")
        return _image_read_result(params)

    decisions = [capability_for_tool(name)]
    if name in {"run", "terminal_run", "terminal_send", "session_run"} and isinstance(params, dict):
        command = params.get("cmd", params.get("command", "")).strip()
        git_cap = git_capability_for_command(command)
        if git_cap and git_cap not in decisions:
            decisions.append(git_cap)
    for extra in secondary_for_tool(name):
        if extra not in decisions:
            decisions.append(extra)

    if name in {"run", "terminal_run", "session_run"} and isinstance(params, dict):
        command = params.get("cmd", params.get("command", ""))
        ok, reason = authorize_command(command)
        if not ok:
            record("dangerous.outside_allowlist", f"mcp.tools/call:{name}", "DENY_COMMAND")
            return {"content": [{"type": "text", "text": f"MCP CONTROL: command denied: {reason}"}], "isError": True}

    for required_cap in _file_decisions(name, params):
        if required_cap not in decisions:
            decisions.append(required_cap)

    ask_capabilities = []
    for required_cap in decisions:
        d = check(required_cap)
        if d.allowed:
            continue
        if d.requires_approval:
            ask_capabilities.append(required_cap)
            continue
        record(required_cap, f"mcp.tools/call:{name}", "DENY")
        return {
            "content": [{"type": "text", "text": f"MCP CONTROL: access denied ({required_cap}); policy is active"}],
            "isError": True,
        }

    if ask_capabilities:
        ask_capabilities = sorted(set(ask_capabilities))
        approval_id = params.get("approval_id") if isinstance(params, dict) else None
        clean_params = dict(params) if isinstance(params, dict) else {}
        clean_params.pop("approval_id", None)
        if approval_id:
            try:
                consume_approval_bundle(approval_id, ask_capabilities, name, clean_params)
            except PermissionError as e:
                for required_cap in ask_capabilities:
                    record(required_cap, f"mcp.tools/call:{name}", f"ASK_DENY:{e}")
                return {"content": [{"type": "text", "text": f"MCP CONTROL: approval denied: {e}"}], "isError": True}
            for required_cap in ask_capabilities:
                record(required_cap, f"mcp.tools/call:{name}", "APPROVED_ALLOW")
        else:
            approval = request_approval_bundle(ask_capabilities, name, clean_params)
            for required_cap in ask_capabilities:
                record(required_cap, f"mcp.tools/call:{name}", "ASK")
            return {
                "content": [{
                    "type": "text",
                    "text": (
                        f"MCP CONTROL: approval required for {', '.join(sorted(ask_capabilities))}; "
                        f"approval_id={approval['approval_id']}; expires_at={approval['expires_at']}"
                    ),
                }],
                "isError": True,
            }

    for required_cap in decisions:
        record(required_cap, f"mcp.tools/call:{name}", "ALLOW")
    return _original(session, name, params, on_progress=on_progress)


mcp_core.call_tool = guarded_call
mcp_core.tool_list = guarded_tool_list

if __name__ == "__main__":
    mcp_server.run_http(host="127.0.0.1", port=8081)
