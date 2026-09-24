# Security model

## Default deny

The policy file starts with `master_lock=true` and every capability set to `deny`.

## Device boundary

This project does not grant Android device permissions. Device capabilities such as shared storage, location, camera, microphone, contacts and SMS remain denied unless a future implementation explicitly adds a separately reviewed adapter.

## Secrets

No API keys, cookies, session tokens or credentials belong in this repository. Local policy lives under `~/.config/mcp-control/policy.json` with mode `0600`.

## Enforcement requirement

The terminal UI is only the control surface. Before MCP execution is wired to this project, the MCP execution adapter must consult the policy engine and deny actions that are not permitted. UI checkboxes must never be treated as security by themselves.
