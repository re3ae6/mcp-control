#!/usr/bin/env python3
"""Compact tabbed terminal control panel for MCP Control."""
from __future__ import annotations
import curses
from core.policy import load_policy
from core.connection import get_connection_status
from core.trusted_control import set_capability, lock, unlock

TABS = [
    ("Overview", None), ("Files", "files"), ("Git", "git"),
    ("Terminal", "terminal"), ("Network", "network"), ("MCP", "mcp"),
    ("Device", "device"), ("Dangerous", "dangerous")
]
STATES = ["deny", "ask", "allow"]


def main(stdscr):
    curses.curs_set(0)
    stdscr.keypad(True)
    policy = load_policy()
    tab = 0
    row = 0

    while True:
        stdscr.erase()
        h, w = stdscr.getmaxyx()
        stdscr.addstr(0, 0, " MCP CONTROL ", curses.A_BOLD)
        lock_state = policy.get("master_lock", True)
        lock_text = "MASTER LOCK: ON" if lock_state else "MASTER LOCK: OFF"
        status = get_connection_status()
        light = "🟢" if status["connected"] else "🔴"
        status_text = f"{light} MCP {status['label']}"
        stdscr.addstr(0, max(0, w-len(status_text)-len(lock_text)-3), status_text, curses.A_BOLD)
        stdscr.addstr(0, max(0, w-len(lock_text)-1), lock_text, curses.A_BOLD)

        x = 0
        for i, (name, _) in enumerate(TABS):
            label = f"[{name}]" if i == tab else f" {name} "
            attr = curses.A_REVERSE if i == tab else curses.A_NORMAL
            if x + len(label) < w:
                stdscr.addstr(2, x, label, attr)
            x += len(label) + 1

        if tab == 0:
            all_items = [item for group in policy["capabilities"].values() for item in group]
            counts = {s: sum(item.get("state") == s for item in all_items) for s in STATES}
            lines = [
                "",
                "Fine-grained, deny-by-default permission controller.",
                "",
                "Effective access: DENY (master lock)" if lock_state else
                "Effective access follows individual permissions.",
                f"DENY={counts['deny']}   ASK={counts['ask']}   ALLOW={counts['allow']}",
                f"Connection: {status['label']}   MCP={status['mcp']}   Proxy={status['proxy']}   Tunnel={status['tunnel']}",
                "",
                "Controls:",
                "  ←/→  change tab    ↑/↓  select    SPACE  cycle DENY→ASK→ALLOW",
                "  L    master lock   S    save     Q    quit",
                "",
                "Android/device access is denied by default.",
            ]
            for n, line in enumerate(lines, 4):
                if n < h - 1:
                    stdscr.addnstr(n, 2, line, max(1, w-4))
        else:
            group = TABS[tab][1]
            items = policy["capabilities"][group]
            row = min(row, max(0, len(items)-1))
            for i, item in enumerate(items):
                if 4 + i >= h - 2:
                    break
                selected = i == row
                state = item.get("state", "deny").upper()
                mark = "☑" if state == "ALLOW" else ("◐" if state == "ASK" else "☐")
                text = f"{mark} {item['label']:<32} [{state}]"
                attr = curses.A_REVERSE if selected else curses.A_NORMAL
                stdscr.addnstr(4 + i, 2, text, max(1, w-4), attr)
            if 4 + len(items) < h:
                stdscr.addnstr(
                    5 + len(items), 2,
                    "SPACE: cycle   L: master lock   S: save   ←/→: tab   Q: quit",
                    max(1, w-4),
                )

        stdscr.refresh()
        key = stdscr.getch()

        if key in (ord("q"), ord("Q")):
            return

        if key == curses.KEY_RIGHT:
            tab = (tab + 1) % len(TABS)
            row = 0
        elif key in (curses.KEY_LEFT, ord("h")):
            tab = (tab - 1) % len(TABS)
            row = 0
        elif key == curses.KEY_DOWN:
            row += 1
        elif key == curses.KEY_UP:
            row = max(0, row - 1)
        elif key in (ord(" "), 10, 13) and tab != 0:
            item = policy["capabilities"][TABS[tab][1]][row]
            current = item.get("state", "deny")
            new_state = STATES[(STATES.index(current) + 1) % len(STATES)]
            set_capability(item["id"], new_state)
            policy = load_policy()
        elif key in (ord("l"), ord("L")):
            if policy.get("master_lock", True):
                unlock()
            else:
                lock()
            policy = load_policy()
        elif key in (ord("s"), ord("S")):
            policy = load_policy()


if __name__ == "__main__":
    curses.wrapper(main)
