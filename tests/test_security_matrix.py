import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from core import approval, policy, trusted_control

from core.capability_map import (
    TOOL_CAPABILITIES,
    TOOL_SECONDARY_CAPABILITIES,
    capability_for_tool,
    git_capability_for_command,
)


class SecurityMatrixTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.policy_dir = root / "policy"
        self.policy_file = self.policy_dir / "policy.json"
        self.audit_file = root / "control_audit.jsonl"
        self.approval_file = root / "approvals.json"
        self.audit_file_2 = root / "audit.jsonl"

        base = {
            "version": 1,
            "master_lock": True,
            "capabilities": {
                "terminal": [
                    {"id": "terminal.bash", "state": "deny"},
                    {"id": "terminal.python", "state": "ask"},
                    {"id": "terminal.process", "state": "allow"},
                ]
            },
        }
        self.policy_dir.mkdir(parents=True)
        self.policy_file.write_text(json.dumps(base), encoding="utf-8")

        self.patches = [
            patch.object(policy, "POLICY_DIR", self.policy_dir),
            patch.object(policy, "POLICY_FILE", self.policy_file),
            patch.object(trusted_control, "AUDIT_FILE", self.audit_file),
            patch.object(approval, "ROOT", root),
            patch.object(approval, "FILE", self.approval_file),
            patch.object(approval, "AUDIT", self.audit_file_2),
        ]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.tmp.cleanup()

    def test_master_lock_is_global_effective_deny(self):
        p = policy.load_policy()
        self.assertEqual(policy.decision(p, "terminal.process"), "deny")
        self.assertEqual(policy.decision(p, "terminal.python"), "deny")
        with self.assertRaises(PermissionError):
            policy.require(p, "terminal.process")

    def test_unknown_capability_is_fail_closed(self):
        p = policy.load_policy()
        self.assertEqual(policy.decision(p, "does.not.exist"), "deny")
        with self.assertRaises(PermissionError):
            policy.require(p, "does.not.exist")

        p["master_lock"] = False
        policy.save_policy(p)
        with self.assertRaises(KeyError):
            trusted_control.set_capability("does.not.exist", "allow")

    def test_master_lock_blocks_capability_mutation(self):
        with self.assertRaisesRegex(PermissionError, "master_lock_active"):
            trusted_control.set_capability("terminal.bash", "allow")
        p = policy.load_policy()
        self.assertEqual(policy.get_state(p, "terminal.bash"), "deny")

    def test_allow_is_explicit(self):
        p = policy.load_policy()
        p["master_lock"] = False
        policy.save_policy(p)
        self.assertEqual(policy.decision(policy.load_policy(), "terminal.process"), "allow")
        policy.require(policy.load_policy(), "terminal.process")

    def test_deny_is_not_executable(self):
        p = policy.load_policy()
        p["master_lock"] = False
        policy.save_policy(p)
        with self.assertRaisesRegex(PermissionError, "access_denied:terminal.bash"):
            policy.require(policy.load_policy(), "terminal.bash")

    def test_ask_requires_exact_one_shot_approval(self):
        p = policy.load_policy()
        p["master_lock"] = False
        policy.save_policy(p)

        item = approval.create("terminal.python", "terminal.run", {"command": "echo ok"})
        with self.assertRaisesRegex(PermissionError, "approval_not_approved"):
            approval.consume(item["approval_id"], "terminal.python", "terminal.run", {"command": "echo ok"})

        approval.approve(item["approval_id"])
        approval.consume(item["approval_id"], "terminal.python", "terminal.run", {"command": "echo ok"})

        with self.assertRaisesRegex(PermissionError, "approval_already_consumed"):
            approval.consume(item["approval_id"], "terminal.python", "terminal.run", {"command": "echo ok"})

    def test_approval_cannot_cross_capability_or_request(self):
        p = policy.load_policy()
        p["master_lock"] = False
        p["capabilities"]["terminal"][0]["state"] = "ask"
        policy.save_policy(p)

        item = approval.create("terminal.python", "terminal.run", {"command": "echo ok"})
        approval.approve(item["approval_id"])
        with self.assertRaisesRegex(PermissionError, "approval_request_mismatch"):
            approval.consume(item["approval_id"], "terminal.python", "terminal.run", {"command": "echo blocked"})

    def test_unlock_requires_local_ui_or_tty(self):
        with patch("sys.stdin.isatty", return_value=False):
            with self.assertRaisesRegex(PermissionError, "trusted_control_unlock_requires_local_confirmation"):
                trusted_control.unlock("UNLOCK")
        trusted_control.unlock("UNLOCK", local_ui=True)
        self.assertFalse(policy.load_policy()["master_lock"])

    def test_dispatch_never_exposes_unlock(self):
        with self.assertRaisesRegex(PermissionError, "trusted_control_action_denied:unlock"):
            trusted_control.dispatch("unlock", confirmation="UNLOCK", local_ui=True)

    def test_explicit_tool_mapping_is_covered_by_policy(self):
        canonical = Path(__file__).resolve().parents[1] / "policies" / "default.json"
        policy_data = json.loads(canonical.read_text(encoding="utf-8"))
        ids = {item["id"] for group in policy_data["capabilities"].values() for item in group}
        operational = set(TOOL_CAPABILITIES.values())
        operational.update(cap for caps in TOOL_SECONDARY_CAPABILITIES.values() for cap in caps)
        self.assertTrue(operational <= ids)
        self.assertEqual(capability_for_tool("not-a-real-tool"), "dangerous.outside_allowlist")
        self.assertEqual(git_capability_for_command("git status --short"), "git.status")
        self.assertIsNone(git_capability_for_command("echo git status"))


if __name__ == "__main__":
    unittest.main()
