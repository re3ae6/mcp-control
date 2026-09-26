#!/usr/bin/env python3
"""Conservative command/path boundary for terminal.bash."""
from __future__ import annotations
import os, re, shlex
from pathlib import Path

ALLOWED_ROOTS = tuple(Path(os.path.expanduser(x)).resolve() for x in ("~/po_recorder", "~/mcp-control", "~/.config/mcp-control"))
PO_RECORDER_ROOT = Path(os.path.expanduser("~/po_recorder")).resolve()
BLOCKED_PREFIXES = ("/sdcard", "/storage", "/system", "/vendor", "/proc", "/sys", "/dev")
DANGEROUS = {"rmdir", "unlink", "shred", "chmod", "chown", "kill", "pkill", "killall", "su", "sudo", "termux-api"}
SHELL_WRAPPERS = {"sh", "bash", "zsh", "fish"}
INTERPRETERS = {"python", "python3", "python3.14", "perl", "ruby", "node", "php"}
CODE_FLAGS = {"-c", "-e", "--eval", "--command"}

def _inside(path: Path) -> bool:
    rp = path.resolve()
    return any(rp == root or root in rp.parents for root in ALLOWED_ROOTS)

def _looks_like_path(token: str) -> bool:
    return token.startswith(("/", "~/", "../", "./")) or "/" in token

def authorize_command(command: str) -> tuple[bool, str]:
    if not isinstance(command, str) or not command.strip(): return False, "empty command"
    if len(command) > 12000: return False, "command too long"
    if "`" in command or re.search(r"\$\([^)]*\)", command): return False, "shell substitution is not allowed"
    if re.search(r"(^|[;&|])\s*(eval|exec)\b", command): return False, "shell metaprogramming is not allowed"
    try: tokens = shlex.split(command, posix=True)
    except ValueError as e: return False, f"invalid shell syntax: {e}"
    if not tokens: return False, "empty command"
    if Path(tokens[0]).name == "rm":
        operands = [x for x in tokens[1:] if not x.startswith("-")]
        if not operands: return False, "rm requires a target"
        if any(x in tokens[1:] for x in ("-r", "-R", "-rf", "-fr")): return False, "recursive rm requires the dedicated delete capability"
        for target in operands:
            if not _looks_like_path(target): return False, "rm target must be explicitly scoped to ~/po_recorder"
            target_path = Path(os.path.expanduser(target)).resolve()
            if not (target_path == PO_RECORDER_ROOT or PO_RECORDER_ROOT in target_path.parents): return False, f"rm target outside ~/po_recorder: {target}"
    for i, tok in enumerate(tokens):
        base = Path(tok).name
        if base in SHELL_WRAPPERS and i + 1 < len(tokens) and tokens[i + 1] in {"-c", "-lc", "-ic"}:
            return False, "nested shell execution is not allowed"
        if base in DANGEROUS: return False, f"dangerous command requires its dedicated capability: {base}"
        if base in INTERPRETERS and i + 1 < len(tokens) and tokens[i + 1] in CODE_FLAGS:
            return False, f"inline interpreter code requires its dedicated capability: {base} {tokens[i+1]}"
    for tok in tokens:
        if not _looks_like_path(tok): continue
        expanded = os.path.expanduser(tok)
        if "://" in expanded: continue
        if any(expanded == x or expanded.startswith(x + "/") for x in BLOCKED_PREFIXES): return False, f"path outside allowlist: {expanded}"
        p = Path(expanded)
        if p.is_absolute() and not _inside(p): return False, f"absolute path outside allowlist: {expanded}"
        if not p.is_absolute() and any(part == ".." for part in p.parts): return False, f"path traversal is not allowed: {tok}"
    return True, "ok"
