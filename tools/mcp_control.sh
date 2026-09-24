#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/mcp-control" || exit 1
exec python3 ui/control_panel.py
