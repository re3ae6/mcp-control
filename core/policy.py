#!/usr/bin/env python3
"""Local deny-by-default policy engine for MCP Control."""
from __future__ import annotations
import json
import os
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT = ROOT / "policies" / "default.json"
POLICY_DIR = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "mcp-control"
POLICY_FILE = POLICY_DIR / "policy.json"
VALID = {"deny", "ask", "allow"}


def _validate_policy(data: dict[str, Any]) -> None:
    if not isinstance(data, dict) or data.get("version") != 1:
        raise ValueError("policy_invalid")
    if not isinstance(data.get("master_lock"), bool):
        raise ValueError("master_lock_invalid")
    custom_paths = data.get("custom_paths", [])
    if not isinstance(custom_paths, list) or any(not isinstance(x, str) or not x.startswith("/") for x in custom_paths):
        raise ValueError("custom_paths_invalid")
    capabilities = data.get("capabilities")
    if not isinstance(capabilities, dict):
        raise ValueError("capabilities_invalid")
    seen = set()
    for group, items in capabilities.items():
        if not isinstance(group, str) or not isinstance(items, list):
            raise ValueError("capability_group_invalid")
        for item in items:
            if not isinstance(item, dict) or not isinstance(item.get("id"), str):
                raise ValueError("capability_entry_invalid")
            capability_id = item["id"]
            if capability_id in seen:
                raise ValueError(f"duplicate_capability:{capability_id}")
            seen.add(capability_id)
            if item.get("state", "deny") not in VALID:
                raise ValueError(f"capability_state_invalid:{capability_id}")


def load_policy() -> dict[str, Any]:
    POLICY_DIR.mkdir(parents=True, exist_ok=True)
    if not POLICY_FILE.exists():
        POLICY_FILE.write_text(DEFAULT.read_text(), encoding="utf-8")
        os.chmod(POLICY_FILE, 0o600)
    try:
        data = json.loads(POLICY_FILE.read_text(encoding="utf-8"))
        _validate_policy(data)
        return data
    except (OSError, json.JSONDecodeError, TypeError, ValueError):
        data = json.loads(DEFAULT.read_text(encoding="utf-8"))
        _validate_policy(data)
        save_policy(data)
        return data


def save_policy(policy: dict[str, Any]) -> None:
    POLICY_DIR.mkdir(parents=True, exist_ok=True)
    tmp = POLICY_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(policy, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(POLICY_FILE)


def set_master_lock(policy: dict[str, Any], locked: bool) -> None:
    policy["master_lock"] = bool(locked)


def set_state(policy: dict[str, Any], capability_id: str, state: str) -> None:
    if state not in VALID:
        raise ValueError(state)
    for group in policy.get("capabilities", {}).values():
        for item in group:
            if item.get("id") == capability_id:
                item["state"] = state
                return
    raise KeyError(capability_id)


def get_state(policy: dict[str, Any], capability_id: str) -> str:
    if policy.get("master_lock", True):
        return "deny"
    for group in policy.get("capabilities", {}).values():
        for item in group:
            if item.get("id") == capability_id:
                return item.get("state", "deny")
    return "deny"


def decision(policy: dict[str, Any], capability_id: str) -> str:
    """Return the effective decision: deny, ask, or allow."""
    return get_state(policy, capability_id)


def capability_ids(policy: dict[str, Any]) -> list[str]:
    return [item["id"] for group in policy.get("capabilities", {}).values() for item in group]


def all_capabilities(policy: dict[str, Any]) -> list[dict[str, Any]]:
    return [item for group in policy.get("capabilities", {}).values() for item in group]


def require(policy: dict[str, Any], capability_id: str) -> None:
    """Enforce a capability. Raises PermissionError unless explicitly allowed."""
    state = decision(policy, capability_id)
    if state == "allow":
        return
    if state == "ask":
        raise PermissionError(f"approval_required:{capability_id}")
    raise PermissionError(f"access_denied:{capability_id}")
