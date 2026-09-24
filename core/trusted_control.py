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
    "unlock",
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
    set_state(policy, capability_id, state)
    save_policy(policy)
    _audit("set_capability", "ALLOW", f"{capability_id}={state}")


def lock() -> None:
    policy = load_policy()
    set_master_lock(policy, True)
    save_policy(policy)
    _audit("lock", "ALLOW")


def unlock() -> None:
    """Explicitly unlock through the local trusted-control/UI path only.

    This primitive is intentionally not exposed as an MCP tool. Ordinary
    MCP requests remain subject to the master lock.
    """
    policy = load_policy()
    set_master_lock(policy, False)
    save_policy(policy)
    _audit("unlock", "ALLOW", "local_trusted_control_only")


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

    if action == "unlock":
        unlock()
        return {"ok": True, "master_lock": False}

    raise AssertionError(action)


if __name__ == "__main__":
    print(json.dumps(read_policy(), ensure_ascii=False, indent=2))
