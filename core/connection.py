#!/usr/bin/env python3
"""Read-only local MCP/Proxy/Tunnel connection status probe."""
from __future__ import annotations
import socket
import subprocess
import urllib.request

def _tcp(port: int, timeout: float = 0.7) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout):
            return True
    except OSError:
        return False

def _http(url: str, timeout: float = 0.8) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return 200 <= r.status < 500
    except Exception:
        return False

def _process(pattern: str) -> bool:
    try:
        p = subprocess.run(["pgrep", "-f", pattern],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           timeout=0.8)
        return p.returncode == 0
    except Exception:
        return False

def get_connection_status() -> dict[str, object]:
    mcp = _tcp(8081)
    proxy = _tcp(18081)
    tunnel = (
        _http("http://127.0.0.1:18080/healthz")
        and _http("http://127.0.0.1:18080/readyz")
        and _process(r"tunnel-client run .*po-termux")
    )
    connected = mcp and proxy and tunnel
    return {
        "connected": connected,
        "label": "CONNECTED" if connected else "DISCONNECTED",
        "mcp": "OK" if mcp else "DOWN",
        "proxy": "OK" if proxy else "DOWN",
        "tunnel": "LIVE / READY" if tunnel else "DOWN",
    }
