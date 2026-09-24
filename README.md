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

## New-device setup

Most of the local setup can be automated, but the secure tunnel credential must remain a manual secret.

1. Install Termux and the required Termux MCP package on the new device.
2. Clone this repository and `po_recorder`, then run the connector from `po_recorder/tools/connect_mcp.sh`.
3. The connector can automatically start the guarded MCP server, create the local CONNECT proxy, and start/reuse the tunnel client.
4. Provide `CONTROL_PLANE_API_KEY` through the device environment; never put the key in Git, scripts, or policy files.
5. Verify that the tunnel reports `LIVE / READY` and that `guarded_server.py` is the process serving MCP on port 8081.
6. The security policy starts locked with all capabilities set to `DENY`.

### What cannot be safely automated

A new device still needs a trusted enrollment step for the tunnel credential. The key is intentionally not generated, copied, or embedded by this project. If the device is replaced, configure the credential again rather than committing or transferring it through the repository.

### Quick start

After the prerequisites and credential are configured, the intended workflow is:

```bash
cd ~/po_recorder && bash tools/connect_mcp.sh
```

The connector pulls the latest code first and performs the remaining local startup checks automatically.
