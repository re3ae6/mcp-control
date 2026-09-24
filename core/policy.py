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


def load_policy() -> dict[str, Any]:
    POLICY_DIR.mkdir(parents=True, exist_ok=True)
    if not POLICY_FILE.exists():
        POLICY_FILE.write_text(DEFAULT.read_text(), encoding="utf-8")
        os.chmod(POLICY_FILE, 0o600)
    return json.loads(POLICY_FILE.read_text(encoding="utf-8"))


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
