# MCP Control Android

Independent Android control surface for the existing Termux/MCP control plane.

Security:
- No API key is stored in the app.
- No storage, location, camera, microphone, contacts, SMS or notification permission is requested.
- The app uses the narrow Termux RUN_COMMAND bridge.
- Only fixed bridge operations are exposed: status, policy, set, lock, audit, start, restart.
- MASTER LOCK remains enforced by the Termux policy layer.
- Unlock is not exposed to the Android app.

Termux must allow external apps and the Android app must be granted Run commands in Termux environment. This is a deliberate trust step.

Build the android directory with Android Studio.
