#!/usr/bin/env python3
"""One-shot exact-request approval engine."""
from __future__ import annotations

import hashlib
import json
import secrets
import fcntl
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from .policy import load_policy, decision

ROOT = Path.home() / ".config" / "mcp-control"
FILE = ROOT / "approvals.json"
AUDIT = ROOT / "control_audit.jsonl"
LOCK = ROOT / "approvals.lock"
TTL_SECONDS = 60


@contextmanager
def _store_lock():
    ROOT.mkdir(parents=True, exist_ok=True)
    with LOCK.open("a+", encoding="utf-8") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(value: datetime) -> str:
    return value.isoformat()


def request_digest(capability: str, tool: str, params: Any) -> str:
    payload = {
        "capability": capability,
        "tool": tool,
        "params": params,
    }
    raw = json.dumps(
        payload,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode()
    return hashlib.sha256(raw).hexdigest()


def _load() -> dict[str, Any]:
    ROOT.mkdir(parents=True, exist_ok=True)
    if not FILE.exists():
        FILE.write_text('{"approvals":[]}\n', encoding="utf-8")
        FILE.chmod(0o600)
    data = json.loads(FILE.read_text(encoding="utf-8"))
    data.setdefault("approvals", [])
    return data


def _save(data: dict[str, Any]) -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    tmp = FILE.with_suffix(".tmp")
    tmp.write_text(
        json.dumps(data, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    tmp.chmod(0o600)
    tmp.replace(FILE)
    FILE.chmod(0o600)


def _audit(action: str, result: str, detail: str = "") -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    row = {"ts": _iso(_now()), "action": action, "result": result}
    if detail:
        row["detail"] = detail
    with AUDIT.open("a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")
    AUDIT.chmod(0o600)


def create(capability: str, tool: str, params: Any) -> dict[str, Any]:
    if decision(load_policy(), capability) != "ask":
        _audit("approval_create", "DENY", f"policy:{decision(load_policy(), capability)}")
        raise PermissionError("approval_not_allowed_by_policy")
    now = _now()
    item = {
        "approval_id": secrets.token_urlsafe(24),
        "capability": capability,
        "tool": tool,
        "request_digest": request_digest(capability, tool, params),
        "created_at": _iso(now),
        "expires_at": _iso(now + timedelta(seconds=TTL_SECONDS)),
        "status": "pending",
        "consumed": False,
    }
    with _store_lock():
        data = _load()
        data["approvals"].append(item)
        _save(data)
    _audit("approval_create", "ALLOW", item["approval_id"])

    return item


def approve(approval_id: str) -> dict[str, Any]:
    with _store_lock():
        data = _load()
        for item in data["approvals"]:
            if item["approval_id"] != approval_id:
                continue
            if item.get("consumed"):
                raise PermissionError("approval_already_consumed")
            if item.get("status") != "pending":
                raise PermissionError("approval_not_pending")
            if _now() >= datetime.fromisoformat(item["expires_at"]):
                item["status"] = "expired"
                _save(data)
                _audit("approval_approve", "DENY", "expired")
                raise PermissionError("approval_expired")
            item["status"] = "approved"
            _save(data)
            _audit("approval_approve", "ALLOW", approval_id)
            return item
    _audit("approval_approve", "DENY", "unknown_approval")
    raise PermissionError("approval_not_found")


def consume(
    approval_id: str,
    capability: str,
    tool: str,
    params: Any,
) -> dict[str, Any]:
    with _store_lock():
        if decision(load_policy(), capability) != "ask":
            _audit("approval_consume", "DENY", f"policy:{decision(load_policy(), capability)}")
            raise PermissionError("approval_blocked_by_policy")

        data = _load()
        digest = request_digest(capability, tool, params)

        for item in data["approvals"]:
            if item["approval_id"] != approval_id:
                continue
            if item.get("consumed"):
                raise PermissionError("approval_already_consumed")
            if item.get("status") != "approved":
                raise PermissionError("approval_not_approved")
            if _now() >= datetime.fromisoformat(item["expires_at"]):
                item["status"] = "expired"
                _save(data)
                raise PermissionError("approval_expired")
            if (
                item["capability"] != capability
                or item["tool"] != tool
                or item["request_digest"] != digest
            ):
                raise PermissionError("approval_request_mismatch")

            item["consumed"] = True
            item["status"] = "consumed"
            item["consumed_at"] = _iso(_now())
            _save(data)
            _audit("approval_consume", "ALLOW", approval_id)
            return item

        raise PermissionError("approval_not_found")


def create_bundle(capabilities: list[str], tool: str, params: Any) -> dict[str, Any]:
    caps = sorted(set(capabilities))
    if not caps:
        raise ValueError("approval_bundle_requires_capabilities")
    policy = load_policy()
    if any(decision(policy, cap) != "ask" for cap in caps):
        _audit("approval_bundle_create", "DENY", "policy")
        raise PermissionError("approval_not_allowed_by_policy")
    now = _now()
    item = {
        "approval_id": secrets.token_urlsafe(24),
        "capabilities": caps,
        "tool": tool,
        "request_digest": request_digest(json.dumps(caps, separators=(",", ":")), tool, params),
        "created_at": _iso(now),
        "expires_at": _iso(now + timedelta(seconds=TTL_SECONDS)),
        "status": "pending",
        "consumed": False,
    }
    with _store_lock():
        data = _load()
        data["approvals"].append(item)
        _save(data)
    _audit("approval_bundle_create", "ALLOW", item["approval_id"])
    return item


def consume_bundle(
    approval_id: str,
    capabilities: list[str],
    tool: str,
    params: Any,
) -> dict[str, Any]:
    caps = sorted(set(capabilities))
    if not caps:
        raise ValueError("approval_bundle_requires_capabilities")
    with _store_lock():
        policy = load_policy()
        if any(decision(policy, cap) != "ask" for cap in caps):
            _audit("approval_bundle_consume", "DENY", "policy")
            raise PermissionError("approval_blocked_by_policy")
        data = _load()
        digest = request_digest(json.dumps(caps, separators=(",", ":")), tool, params)
        for item in data["approvals"]:
            if item["approval_id"] != approval_id:
                continue
            if item.get("consumed"):
                raise PermissionError("approval_already_consumed")
            if item.get("status") != "approved":
                raise PermissionError("approval_not_approved")
            if _now() >= datetime.fromisoformat(item["expires_at"]):
                item["status"] = "expired"
                _save(data)
                raise PermissionError("approval_expired")
            if (
                sorted(item.get("capabilities", [])) != caps
                or item.get("tool") != tool
                or item.get("request_digest") != digest
            ):
                raise PermissionError("approval_request_mismatch")
            item["consumed"] = True
            item["status"] = "consumed"
            item["consumed_at"] = _iso(_now())
            _save(data)
            _audit("approval_bundle_consume", "ALLOW", approval_id)
            return item
        raise PermissionError("approval_not_found")
