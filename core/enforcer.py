#!/usr/bin/env python3
"""Single enforcement boundary for MCP Control capabilities."""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
import json
from .approval import create as create_approval, consume as _consume_approval
from .policy import load_policy, decision

AUDIT_DIR = Path.home() / ".config" / "mcp-control"
AUDIT_FILE = AUDIT_DIR / "audit.jsonl"

@dataclass(frozen=True)
class Decision:
    capability: str
    state: str
    allowed: bool
    requires_approval: bool



def request_approval(capability: str, tool: str, params) -> dict:
    """Create a short-lived approval bound to the exact request."""
    return create_approval(capability, tool, params)


def consume_approval(
    approval_id: str,
    capability: str,
    tool: str,
    params,
) -> dict:
    """Consume one exact approval; mismatches and replay are denied."""
    return _consume_approval(
        approval_id,
        capability,
        tool,
        params,
    )

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
