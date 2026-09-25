import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from core import approval, enforcer, policy


class GatewayApprovalE2ETests(unittest.TestCase):
    """Policy -> approval -> enforcement -> action boundary tests."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.policy_file = root / "policy.json"
        self.approval_file = root / "approvals.json"
        self.audit_file = root / "audit.jsonl"
        self.policy_file.write_text(json.dumps({
            "version": 1,
            "master_lock": False,
            "capabilities": {
                "terminal": [
                    {"id": "terminal.bash", "state": "deny"},
                    {"id": "terminal.python", "state": "ask"},
                    {"id": "terminal.process", "state": "allow"},
                ]
            },
        }), encoding="utf-8")
        self.patches = [
            patch.object(policy, "POLICY_DIR", root),
            patch.object(policy, "POLICY_FILE", self.policy_file),
            patch.object(approval, "ROOT", root),
            patch.object(approval, "FILE", self.approval_file),
            patch.object(approval, "AUDIT", self.audit_file),
        ]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.tmp.cleanup()

    def gateway_execute(self, capability, tool, params, action):
        decision = enforcer.check(capability)
        if decision.state == "allow":
            return action()
        if decision.state == "ask":
            item = enforcer.request_approval(capability, tool, params)
            approval.approve(item["approval_id"])
            enforcer.consume_approval(
                item["approval_id"], capability, tool, params
            )
            return action()
        raise PermissionError("gateway_denied")

    def test_allow_reaches_action_without_approval(self):
        action = Mock(return_value={"ok": True})
        self.assertEqual(
            self.gateway_execute(
                "terminal.process", "process.list", {}, action
            ),
            {"ok": True},
        )
        action.assert_called_once_with()

    def test_ask_requires_approval_before_action(self):
        action = Mock(return_value={"ok": True})
        with patch.object(
            enforcer, "consume_approval",
            wraps=enforcer.consume_approval,
        ) as consume:
            result = self.gateway_execute(
                "terminal.python", "terminal.run",
                {"command": "echo ok"}, action
            )
        self.assertEqual(result, {"ok": True})
        action.assert_called_once_with()
        consume.assert_called_once()

    def test_deny_never_reaches_action(self):
        action = Mock(return_value={"ok": True})
        with self.assertRaisesRegex(PermissionError, "gateway_denied"):
            self.gateway_execute(
                "terminal.bash", "terminal.run",
                {"command": "echo blocked"}, action
            )
        action.assert_not_called()

    def test_master_lock_turns_allow_into_deny(self):
        p = policy.load_policy()
        p["master_lock"] = True
        policy.save_policy(p)
        action = Mock(return_value={"ok": True})
        with self.assertRaisesRegex(PermissionError, "gateway_denied"):
            self.gateway_execute(
                "terminal.process", "process.list", {}, action
            )
        action.assert_not_called()

    def test_approval_is_bound_to_exact_gateway_request(self):
        item = enforcer.request_approval(
            "terminal.python", "terminal.run", {"command": "echo ok"}
        )
        approval.approve(item["approval_id"])
        with self.assertRaisesRegex(
            PermissionError, "approval_request_mismatch"
        ):
            enforcer.consume_approval(
                item["approval_id"], "terminal.python", "terminal.run",
                {"command": "echo blocked"},
            )

    def test_consumed_approval_cannot_replay(self):
        item = enforcer.request_approval(
            "terminal.python", "terminal.run", {"command": "echo ok"}
        )
        approval.approve(item["approval_id"])
        enforcer.consume_approval(
            item["approval_id"], "terminal.python",
            "terminal.run", {"command": "echo ok"}
        )
        with self.assertRaisesRegex(
            PermissionError, "approval_already_consumed"
        ):
            enforcer.consume_approval(
                item["approval_id"], "terminal.python",
                "terminal.run", {"command": "echo ok"}
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
