# MCP Control

Fine-grained, deny-by-default permission control for MCP agents running with Termux.

## Design goals

- Every capability is independently controllable.
- Default state is `DENY`.
- `ASK` can require a fresh approval before an action.
- Permissions are grouped into tabs to keep the control surface compact.
- Android shared storage, location, camera, microphone, contacts, SMS and other device access are denied by default and are not enabled by this project.
- Secrets are never stored in the repository.

## Tabs

1. Overview
2. Files
3. Git
4. Terminal
5. Network
6. MCP
7. Device
8. Dangerous

## Current stage

MVP policy engine + terminal control panel. The policy layer is designed to become the enforcement boundary for MCP execution; the UI is not a decorative checkbox layer.

## Run

```bash
python3 -m ui.control_panel
```

Policy is stored locally at `~/.config/mcp-control/policy.json` and is intentionally outside the repository.
