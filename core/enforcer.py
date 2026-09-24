#!/usr/bin/env python3
"""Single enforcement boundary for MCP Control capabilities."""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
import json
from .policy import load_policy, decision

AUDIT_DIR = Path.home() / ".config" / "mcp-control"
AUDIT_FILE = AUDIT_DIR / "audit.jsonl"

@dataclass(frozen=True)
class Decision:
    capability: str
    state: str
    allowed: bool
    requires_approval: bool


def check(capability: str) -> Decision:
    policy = load_policy()
    state = decision(policy, capability)
    return Decision(capability, state, state == "allow", state == "ask")


def record(capability: str, action: str, result: str) -> None:
    AUDIT_DIR.mkdir(parents=True, exist_ok=True)
    event = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "capability": capability,
        "action": action,
        "result": result,
    }
    with AUDIT_FILE.open("a", encoding="utf-8") as f:
        f.write(json.dumps(event, ensure_ascii=False) + "\n")
