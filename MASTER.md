# MCP Control — MASTER

## 1. Mission
Local Android/Termux MCP permission gateway.
Default security posture: DENY.
Normal MCP execution remains blocked while MASTER LOCK is enabled.

## 2. Security Model
- Default DENY.
- Effective states: deny / ask / allow.
- MASTER LOCK is a hard global override: every normal MCP capability becomes DENY.
- Unknown/unmapped tools resolve to `dangerous.outside_allowlist`.
- MCP tools cannot grant themselves permissions.
- ASK is not ALLOW.
- Approval is one-shot and request-bound.

## 3. Architecture

Local Control UI
    -> Policy Manager
    -> Permission Gateway / Enforcer
    -> Approval Engine
    -> Command + Path Guard
    -> Audit
    -> MCP execution

## 4. Approval Engine
Every ASK approval contains:
- approval_id
- capability
- tool
- request_digest
- created_at
- expires_at
- status
- consumed

Rules:
- approval expires after 60 seconds.
- approval must be explicitly approved.
- approval is bound to exact capability + tool + params.
- mismatch is denied.
- consumed approval cannot be replayed.
- MASTER LOCK blocks both approval creation and consumption through policy enforcement.

## 5. Gateway
`runtime/guarded_server.py` is the normal MCP enforcement boundary.

Flow:
1. Map MCP tool to capability.
2. Apply command guard where applicable.
3. Evaluate effective policy.
4. DENY -> reject.
5. ASK -> create approval.
6. Approved exact request -> consume approval and execute once.
7. ALLOW -> execute directly.
8. Audit every security decision.

## 6. Command / Path Safety
Allowed roots:
- `~/po_recorder`
- `~/mcp-control`

Protected system/storage paths are denied.
Dangerous shell commands and shell wrappers are denied.
Inline interpreter execution is denied through the command guard.
Unknown/outside paths are denied.

## 7. Trusted Control
`core/trusted_control.py` provides the narrow local policy-management surface:
- read_policy
- set_capability
- lock

`unlock` is local-UI-only recovery, is not exposed through `dispatch`/MCP, and requires the exact interactive confirmation `UNLOCK` from a TTY.

Important:
trusted_control is a control-plane module, not yet a complete OS-level trust boundary. Same-user local processes may still invoke Python modules directly. A future hardened admin/UI boundary must address this.

## 8. Connection State
`core/connection.py` reports:
- MCP 127.0.0.1:8081
- proxy 127.0.0.1:18081
- tunnel health/readiness

UI reports actual CONNECTED/DISCONNECTED state.

## 9. UI
Exactly eight areas:
1. Overview
2. Files
3. Git
4. Terminal
5. Network
6. MCP
7. Device
8. Dangerous

UI requirements:
- Persistent Connect / Refresh action.
- Persistent Master Lock control.
- Green/red status indicators for MCP, Proxy and Tunnel.
- Yellow status for connecting/waiting.
- Capability rows expose their current DENY / ASK / ALLOW state.
- UI must remain usable in offline/bridge-failure mode.
- Tabs are functional control areas, not decorative navigation.

## 10. Android Bridge
- Android package: `com.re3ae6.mcpcontrol`.
- Minimum SDK 29; target/compile SDK 35.
- Bridge invokes only the fixed local `tools/mobile_control.sh` entrypoint through Termux RUN_COMMAND.
- No arbitrary shell command field is exposed by the Android UI.
- No API key is embedded in the APK or repository.
- Current bridge commands are limited to policy/status/set/lock/unlock/start/restart/audit.
- Termux bridge failures must leave the app open in offline mode.

## 11. Mobile Control
`tools/mobile_control.sh` is the narrow Android-to-Termux control surface.
Current actions:
- `status`
- `policy`
- `set <capability> <deny|ask|allow>`
- `lock`
- local-confirmed `unlock UNLOCK`
- `start`
- `restart`
- `audit`

Connection status JSON uses:
- `connected`
- `label`
- `mcp`
- `proxy`
- `tunnel`

## 12. Audit
Security decisions are recorded under:
`~/.config/mcp-control/`

Files:
- `audit.jsonl`
- `control_audit.jsonl`
- `approvals.json`

Sensitive control files use restrictive permissions.

## 13. Current Verified State
Verified:
- Python compilation passes.
- `git diff --check` passes.
- MASTER LOCK effective DENY passes.
- Approval create/approve passes.
- Exact request digest enforcement passes.
- Mismatch rejection passes.
- One-shot consume passes.
- Replay rejection passes.
- Gateway ASK -> approval -> execution passes.
- Gateway mismatch rejection passes.
- Gateway replay rejection passes.
- Gateway MASTER LOCK rejection passes.
- Final tested MASTER LOCK state is TRUE.
- Android debug build pipeline is configured in GitHub Actions.
- Termux RUN_COMMAND bridge source is present.
- Android UI has all eight required areas.
- MCP / Proxy / Tunnel status indicators are wired to the actual connection-status keys.

## 14. Known Remaining Hardening
- Replace coarse tool-to-capability mappings with explicit granular mappings.
- Harden the trusted control boundary.
- Add dedicated administrative authentication/authorization appropriate for the local Android environment.
- Add automated regression tests.
- Review audit integrity and rotation.
- Review all network and device capabilities.
- Ensure every displayed capability has an intentional action/mapping; empty groups must not masquerade as functional controls.
- Replace fixed-delay bridge polling with robust result correlation/timeout handling.
- Keep dangerous capabilities explicitly denied unless deliberately authorized.

## 15. Non-Negotiable Rules
- Never disable MASTER LOCK merely to bypass a test.
- Never treat ASK as ALLOW.
- Never execute an unknown capability.
- Never reuse an approval.
- Never accept an approval for a different request.
- Never allow path traversal outside the declared roots.
- Never allow dangerous shell escape through wrappers or interpolation.
- Keep TRADING / unrelated external automation disabled by default.

## 16. Definition of Done
The control plane is complete only when:
- every MCP tool has an explicit capability mapping;
- all normal execution crosses the gateway;
- ASK requires exact one-shot approval;
- MASTER LOCK is a hard global deny;
- command/path enforcement is active;
- audit coverage is complete;
- trusted administration is isolated from normal MCP execution;
- automated security regression tests pass;
- Git history contains only intentional, reviewed changes.

## 17. v2 Build Progress
- Control UI implemented as eight-area granular permission console.
- Gateway supports multi-capability decisions per MCP tool.
- File operations require both operation capability and path-scope capability.
- Terminal command Git operations are checked against dedicated git capabilities.
- Unknown tools remain fail-closed to `dangerous.outside_allowlist`.
- Launcher: `tools/mcp_control.sh`.
- Android app bridge: `tools/mobile_control.sh`.
- Android status lights now consume the actual `mcp/proxy/tunnel` status fields.
- The next implementation step is to make each of the eight areas expose concrete, correctly mapped actions rather than policy-only rows.

## 18. Latest UI Fixes
- Reworked the Android UI into a light/cream, spacious security-dashboard layout with rounded controls, card-based hierarchy, clean alignment, and English labels for reliable rendering.
- Android launcher now uses the supplied PNG launcher assets for both android:icon and android:roundIcon; the previous green vector launcher fallback was removed.
- Fixed Android Java compilation failure in metric cards by returning real `View` objects instead of extracting them through `getTag()`.
- GitHub Actions workflow retains both automatic `push` builds and manual `workflow_dispatch` builds.

- Fixed Android bridge source formatting/build issue.
- Fixed connection-status field mismatch that caused false red indicators.
- Fixed action busy-state handling so delayed refreshes are not permanently blocked.
- Connect / Refresh remains available after connection attempts.
- MASTER LOCK remains the global effective DENY override.

## 19. Android Connection + Launcher Repair
- Fixed the launcher manifest so both normal and round launcher icons point to the supplied PNG mipmap assets.
- Removed the unused green vector launcher fallback resource.
- Added a narrow Android connect bridge action that starts the existing Termux MCP recovery path with MCP_SKIP_GIT_PULL=1, preventing a UI refresh from pulling or changing the po_recorder checkout.
- start and restart through the Android bridge also skip Git pulls; restart retains the explicit force-restart behavior.
- Connect / Refresh now actually invokes the bridge connector, polls real MCP/Proxy/Tunnel status until ready, then loads policy.
- Android bridge results are stored per command (status, policy, connect, etc.) to prevent result races.
- Termux launch failures and returned connection errors are surfaced in the UI instead of being silently swallowed.
- Termux RunCommand error bundles (`err` / `errmsg`) are captured and correlated per command; stale command results are cleared before each new request.
