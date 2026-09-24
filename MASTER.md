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

`unlock` is intentionally absent.

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

## 10. Audit
Security decisions are recorded under:
`~/.config/mcp-control/`

Files:
- `audit.jsonl`
- `control_audit.jsonl`
- `approvals.json`

Sensitive control files use restrictive permissions.

## 11. Current Verified State
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

## 12. Known Remaining Hardening
- Replace coarse tool-to-capability mappings with explicit granular mappings.
- Harden the trusted control boundary.
- Add dedicated administrative authentication/authorization appropriate for the local Android environment.
- Add automated regression tests.
- Review audit integrity and rotation.
- Review all network and device capabilities.
- Keep dangerous capabilities explicitly denied unless deliberately authorized.

## 13. Non-Negotiable Rules
- Never disable MASTER LOCK merely to bypass a test.
- Never treat ASK as ALLOW.
- Never execute an unknown capability.
- Never reuse an approval.
- Never accept an approval for a different request.
- Never allow path traversal outside the declared roots.
- Never allow dangerous shell escape through wrappers or interpolation.
- Keep TRADING / unrelated external automation disabled by default.

## 14. Definition of Done
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
