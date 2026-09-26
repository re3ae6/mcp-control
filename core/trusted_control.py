#!/usr/bin/env python3
"""Trusted local control primitives for MCP Control policy management."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .policy import load_policy, save_policy, set_master_lock, set_state

AUDIT_FILE = Path.home() / ".config" / "mcp-control" / "control_audit.jsonl"

ALLOWED_ACTIONS = {
    "read_policy",
    "set_capability",
    "lock",
}


def _audit(action: str, result: str, detail: str = "") -> None:
    AUDIT_FILE.parent.mkdir(parents=True, exist_ok=True)
    entry = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "action": action,
        "result": result,
    }
    if detail:
        entry["detail"] = detail
    with AUDIT_FILE.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def read_policy() -> dict[str, Any]:
    policy = load_policy()
    _audit("read_policy", "ALLOW")
    return policy


def set_capability(capability_id: str, state: str) -> None:
    policy = load_policy()
    if policy.get("master_lock", True):
        _audit("set_capability", "DENY", f"master_lock:{capability_id}={state}")
        raise PermissionError("master_lock_active")
    set_state(policy, capability_id, state)
    save_policy(policy)
    _audit("set_capability", "ALLOW", f"{capability_id}={state}")


def _sync_custom_storage_capabilities(policy: dict[str, Any]) -> None:
    """Selected folders get ordinary file operations; broader storage remains denied."""
    allowed = bool(policy.get("custom_paths"))
    for capability_id in ("files.custom", "files.read", "files.write", "files.list", "files.search"):
        try:
            set_state(policy, capability_id, "allow" if allowed else "deny")
        except KeyError:
            pass


def add_custom_path(path: str) -> None:
    path = str(path).strip()
    if not path.startswith("/"):
        raise ValueError("custom_path_must_be_absolute")
    policy = load_policy()
    if policy.get("master_lock", True):
        _audit("add_custom_path", "DENY", f"master_lock:{path}")
        raise PermissionError("master_lock_active")
    paths = policy.setdefault("custom_paths", [])
    if path not in paths:
        paths.append(path)
    _sync_custom_storage_capabilities(policy)
    save_policy(policy)
    _audit("add_custom_path", "ALLOW", path)


def remove_custom_path(path: str) -> None:
    path = str(path).strip()
    policy = load_policy()
    if policy.get("master_lock", True):
        _audit("remove_custom_path", "DENY", f"master_lock:{path}")
        raise PermissionError("master_lock_active")
    paths = policy.setdefault("custom_paths", [])
    if path in paths:
        paths.remove(path)
    _sync_custom_storage_capabilities(policy)
    save_policy(policy)
    _audit("remove_custom_path", "ALLOW", path)


def lock() -> None:
    policy = load_policy()
    set_master_lock(policy, True)
    save_policy(policy)
    _audit("lock", "ALLOW")


def unlock(confirmation: str, local_ui: bool = False) -> None:
    """Unlock only through an explicit local UI recovery path."""
    import sys
    if confirmation != "UNLOCK" or not (sys.stdin.isatty() or local_ui):
        _audit("unlock", "DENY", "interactive_confirmation_required")
        raise PermissionError("trusted_control_unlock_requires_local_confirmation")
    policy = load_policy()
    set_master_lock(policy, False)
    save_policy(policy)
    _audit("unlock", "ALLOW", "local_ui_confirmed")


def dispatch(action: str, **kwargs: Any) -> Any:
    if action not in ALLOWED_ACTIONS:
        _audit(action, "DENY", "action_not_allowed")
        raise PermissionError(f"trusted_control_action_denied:{action}")

    if action == "read_policy":
        return read_policy()

    if action == "set_capability":
        set_capability(kwargs["capability_id"], kwargs["state"])
        return {"ok": True}

    if action == "lock":
        lock()
        return {"ok": True, "master_lock": True}

    raise AssertionError(action)


if __name__ == "__main__":
    print(json.dumps(read_policy(), ensure_ascii=False, indent=2))
