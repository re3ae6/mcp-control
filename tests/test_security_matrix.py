import json
import sys
import tempfile
import types
import unittest
from unittest import mock
from pathlib import Path
from unittest.mock import patch

from core import approval, policy, trusted_control

from core.capability_map import (
    TOOL_CAPABILITIES,
    TOOL_SECONDARY_CAPABILITIES,
    AREA_TOOL_ACTIONS,
    CONTROL_ONLY_CAPABILITIES,
    capability_for_tool,
    git_capability_for_command,
    file_capabilities_for_path,
    file_capabilities_for_params,
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
                    {"id": "terminal.run", "state": "deny"},
                ],
                "files": [
                    {"id": "files.custom", "state": "deny"},
                    {"id": "files.read", "state": "deny"},
                    {"id": "files.write", "state": "deny"},
                    {"id": "files.list", "state": "deny"},
                    {"id": "files.search", "state": "deny"},
                    {"id": "files.mkdir", "state": "deny"},
                    {"id": "files.context", "state": "deny"},
                    {"id": "files.history", "state": "deny"},
                    {"id": "files.changes", "state": "deny"},
                    {"id": "files.shared_storage", "state": "deny"},
                ],
                "mcp": [
                    {"id": "mcp.execute", "state": "deny"},
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
            patch.object(approval, "LOCK", root / "approvals.lock"),
            patch.object(approval, "AUDIT", self.audit_file_2),
        ]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.tmp.cleanup()

    def test_image_process_is_a_file_operation_not_a_camera_operation(self):
        self.assertEqual(capability_for_tool("image_process"), "files.read")
        caps = file_capabilities_for_params(
            "image_process",
            {
                "input": "/storage/emulated/0/Download/Chatgpt/photo.png",
                "output": "/storage/emulated/0/Download/Chatgpt/probe.png",
            },
        )
        self.assertIn("files.read", caps)
        self.assertIn("files.write", caps)
        self.assertNotIn("device.camera", caps)

    def test_custom_folder_requires_operation_and_scope(self):
        p = policy.load_policy()
        p["master_lock"] = False
        p["custom_paths"] = ["/storage/emulated/0/Download/Chatgpt"]
        p["capabilities"]["files"] = [
            {"id": "files.custom", "state": "allow"},
            {"id": "files.read", "state": "deny"},
            {"id": "files.write", "state": "allow"},
        ]
        policy.save_policy(p)

        self.assertEqual(
            file_capabilities_for_path("read", "/storage/emulated/0/Download/Chatgpt/data.jsonl"),
            ["files.read", "files.custom"],
        )
        self.assertEqual(
            file_capabilities_for_path("write", "/storage/emulated/0/Download/Chatgpt/out.jsonl"),
            ["files.write", "files.custom"],
        )

    def test_selected_folder_is_exact_scope_and_sibling_is_denied(self):
        p = policy.load_policy()
        p["master_lock"] = False
        p["custom_paths"] = ["/storage/emulated/0/Download/Chatgpt/"]
        policy.save_policy(p)

        trusted_control._sync_custom_storage_capabilities(p)
        policy.save_policy(p)

        self.assertEqual(
            file_capabilities_for_path("read", "/storage/emulated/0/Download/Chatgpt/data.jsonl"),
            ["files.read", "files.custom"],
        )
        self.assertEqual(
            file_capabilities_for_path("write", "/storage/emulated/0/Download/Chatgpt/out.jsonl"),
            ["files.write", "files.custom"],
        )
        self.assertEqual(
            file_capabilities_for_path("read", "/storage/emulated/0/Download/other.jsonl"),
            ["files.read", "files.shared_storage"],
        )

    def test_selected_folder_auto_grants_basic_file_operations_only(self):
        p = policy.load_policy()
        p["master_lock"] = False
        policy.save_policy(p)
        trusted_control.add_custom_path("/storage/emulated/0/Download/Chatgpt/")
        p = policy.load_policy()

        for capability_id in ("files.custom", "files.read", "files.write", "files.list", "files.search"):
            self.assertEqual(policy.decision(p, capability_id), "allow", capability_id)
        for capability_id in ("files.mkdir", "files.context", "files.history", "files.changes", "files.shared_storage"):
            self.assertEqual(policy.decision(p, capability_id), "deny", capability_id)

    def test_invalid_custom_path_policy_recovers_to_default_deny(self):
        p = policy.load_policy()
        p["master_lock"] = False
        p["custom_paths"] = ["/home/runner/private"]
        policy.save_policy(p)

        recovered = policy.load_policy()
        self.assertTrue(recovered["master_lock"])
        self.assertEqual(recovered.get("custom_paths", []), [])

    def test_sdcard_alias_is_canonicalized_to_primary_shared_storage(self):
        p = policy.load_policy()
        p["master_lock"] = False
        policy.save_policy(p)
        trusted_control.add_custom_path("/sdcard/Download/chatgpt")
        stored = policy.load_policy()["custom_paths"]
        self.assertEqual(stored, ["/storage/emulated/0/Download/chatgpt"])
        self.assertEqual(
            file_capabilities_for_path(
                "read",
                "/storage/emulated/0/Download/chatgpt/photo.png",
            ),
            ["files.read", "files.custom"],
        )

    def test_selected_folder_can_be_any_subfolder_in_shared_storage(self):
        p = policy.load_policy()
        p["master_lock"] = False
        policy.save_policy(p)
        trusted_control.add_custom_path("/storage/emulated/0/Download/SomeOtherFolder")
        self.assertEqual(
            policy.load_policy()["custom_paths"],
            ["/storage/emulated/0/Download/SomeOtherFolder"],
        )

    def test_selected_folder_root_cannot_be_granted(self):
        p = policy.load_policy()
        p["master_lock"] = False
        policy.save_policy(p)
        with self.assertRaisesRegex(ValueError, "custom_path_must_be_specific_folder"):
            trusted_control.add_custom_path("/storage/emulated/0")

    def test_selected_folder_removal_revokes_basic_file_operations(self):
        p = policy.load_policy()
        p["master_lock"] = False
        policy.save_policy(p)
        trusted_control.add_custom_path("/storage/emulated/0/Download/Chatgpt")
        trusted_control.remove_custom_path("/storage/emulated/0/Download/Chatgpt/")
        p = policy.load_policy()
        for capability_id in ("files.custom", "files.read", "files.write", "files.list", "files.search"):
            self.assertEqual(policy.decision(p, capability_id), "deny", capability_id)

    def test_custom_path_does_not_enable_shared_storage(self):
        p = policy.load_policy()
        p["master_lock"] = False
        p["custom_paths"] = ["/storage/emulated/0/Download/Chatgpt"]
        policy.save_policy(p)

        trusted_control._sync_custom_storage_capabilities(p)
        policy.save_policy(p)
        self.assertEqual(policy.decision(p, "files.custom"), "allow")
        self.assertEqual(policy.decision(p, "files.read"), "allow")
        self.assertEqual(policy.decision(p, "files.write"), "allow")
        self.assertEqual(policy.decision(p, "files.list"), "allow")
        self.assertEqual(policy.decision(p, "files.search"), "allow")
        self.assertEqual(policy.decision(p, "files.shared_storage"), "deny")

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

    def test_malformed_policy_fails_closed_to_canonical_default(self):
        p = policy.load_policy()
        p["master_lock"] = False
        p["capabilities"]["terminal"][0]["state"] = "allow"
        policy.save_policy(p)
        self.policy_file.write_text(json.dumps({
            "version": 1, "master_lock": "false", "capabilities": p["capabilities"]
        }), encoding="utf-8")
        recovered = policy.load_policy()
        self.assertTrue(recovered["master_lock"])
        self.assertEqual(policy.decision(recovered, "terminal.bash"), "deny")

    def test_duplicate_capability_ids_fail_closed(self):
        p = policy.load_policy()
        p["master_lock"] = False
        first = p["capabilities"]["terminal"][0]
        p["capabilities"]["terminal"].append(dict(first))
        policy.save_policy(p)
        recovered = policy.load_policy()
        self.assertTrue(recovered["master_lock"])
        self.assertEqual(policy.decision(recovered, first["id"]), "deny")

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


    def _guarded_module(self):
        fake_core = types.ModuleType("termux_mcp.mcp_core")
        fake_core.call_tool = mock.Mock(return_value={"ok": True})
        fake_core.tool_list = mock.Mock(return_value={"tools": []})
        fake_server = types.ModuleType("termux_mcp.mcp_server")
        fake_server.run_http = mock.Mock()
        fake_pkg = types.ModuleType("termux_mcp")
        fake_pkg.mcp_core = fake_core
        fake_pkg.mcp_server = fake_server
        with patch.dict(sys.modules, {
            "termux_mcp": fake_pkg,
            "termux_mcp.mcp_core": fake_core,
            "termux_mcp.mcp_server": fake_server,
        }):
            import importlib
            sys.modules.pop("runtime.guarded_server", None)
            return importlib.import_module("runtime.guarded_server")

    def test_read_image_uses_existing_read_tool_and_returns_native_resource(self):
        module = self._guarded_module()
        p = policy.load_policy()
        p["master_lock"] = False
        p["custom_paths"] = ["/storage/emulated/0/Download/Chatgpt"]
        p["capabilities"]["files"] = [
            {"id": "files.custom", "state": "allow"},
            {"id": "files.read", "state": "allow"},
            {"id": "files.write", "state": "deny"},
        ]
        policy.save_policy(p)
        with tempfile.TemporaryDirectory() as d:
            image_path = Path(d) / "nested" / "photo.jpg"
            image_path.parent.mkdir()
            image_path.write_bytes(b"\\x89PNG\\r\\n\\x1a\\nimage")
            before = sorted(str(x.relative_to(Path(d))) for x in Path(d).rglob("*"))
            with patch.object(module, "file_scope", return_value="files.custom"), patch.object(module, "record"):
                result = module.guarded_call(None, "read", {"path": str(image_path)})
            after = sorted(str(x.relative_to(Path(d))) for x in Path(d).rglob("*"))
            self.assertFalse(result.get("isError", False))
            self.assertEqual(result["content"][0]["type"], "resource")
            self.assertEqual(result["content"][0]["resource"]["mimeType"], "image/png")
            self.assertEqual(result["content"][0]["resource"]["uri"], "mcp-control://image/photo.jpg")
            self.assertEqual(result["content"][0]["resource"]["blob"], __import__("base64").b64encode(image_path.read_bytes()).decode("ascii"))
            self.assertEqual(before, after)

    def test_read_image_denies_immediately_when_scope_is_revoked(self):
        module = self._guarded_module()
        p = policy.load_policy()
        p["master_lock"] = False
        p["capabilities"]["files"] = [
            {"id": "files.read", "state": "allow"},
            {"id": "files.custom", "state": "deny"},
        ]
        policy.save_policy(p)
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as image_file:
            image_file.write(b"\\x89PNG\\r\\n\\x1a\\nimage")
            image_path = Path(image_file.name)
        try:
            with patch.object(module, "file_scope", return_value="files.custom"), patch.object(module, "record"):
                result = module.guarded_call(None, "read", {"path": str(image_path)})
            self.assertTrue(result.get("isError", False))
            self.assertIn("files.custom", result["content"][0]["text"])
        finally:
            image_path.unlink(missing_ok=True)

    def test_guarded_server_tool_list_does_not_add_image_read(self):
        module = self._guarded_module()
        result = module.guarded_tool_list()
        names = {item["name"] for item in result["tools"]}
        self.assertNotIn("image_read", names)

    def test_guarded_server_tool_list_exposes_image_list(self):
        module = self._guarded_module()
        result = module.guarded_tool_list()
        names = {item["name"] for item in result["tools"]}
        self.assertIn("image_list", names)

    def test_image_list_is_scoped_and_returns_current_images(self):
        module = self._guarded_module()
        p = policy.load_policy()
        p["master_lock"] = False
        p["custom_paths"] = ["/storage/emulated/0/Download/Chatgpt"]
        p["capabilities"]["files"] = [
            {"id": "files.custom", "state": "allow"},
            {"id": "files.read", "state": "allow"},
            {"id": "files.write", "state": "deny"},
            {"id": "files.list", "state": "allow"},
        ]
        policy.save_policy(p)
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "new.png").write_bytes(b"png")
            (root / "note.txt").write_text("ignore", encoding="utf-8")
            with patch.object(module, "file_scope", return_value="files.custom"), patch.object(module, "record"):
                result = module.guarded_call(None, "image_list", {"path": str(root)})
            self.assertFalse(result.get("isError", False))
            payload = json.loads(result["content"][0]["text"])
            self.assertEqual(payload["folder"], str(root.resolve()))
            self.assertEqual([x["name"] for x in payload["images"]], ["new.png"])

    def test_guarded_server_deny_never_executes_original(self):
        module = self._guarded_module()
        deny = types.SimpleNamespace(allowed=False, requires_approval=False)
        with patch.object(module, "check", return_value=deny), patch.object(module, "record") as record:
            result = module.guarded_call(None, "run", {"cmd": "echo ok"})
        self.assertTrue(result["isError"])
        self.assertEqual(module._original.call_count, 0)
        record.assert_called()

    def test_guarded_server_allow_executes_once_after_all_checks(self):
        module = self._guarded_module()
        allow = types.SimpleNamespace(allowed=True, requires_approval=False)
        with patch.object(module, "check", return_value=allow), patch.object(module, "authorize_command", return_value=(True, "")), patch.object(module, "record"):
            result = module.guarded_call("s", "run", {"cmd": "echo ok"})
        self.assertEqual(result, {"ok": True})
        self.assertEqual(module._original.call_count, 1)

    def test_guarded_server_ask_stops_before_execution_and_returns_approval(self):
        module = self._guarded_module()
        ask = types.SimpleNamespace(allowed=False, requires_approval=True)
        approval_item = {"approval_id": "a1", "expires_at": "2099-01-01T00:00:00+00:00"}
        with patch.object(module, "check", return_value=ask), patch.object(module, "authorize_command", return_value=(True, "")), patch.object(module, "request_approval_bundle", return_value=approval_item), patch.object(module, "record"):
            result = module.guarded_call(None, "run", {"cmd": "echo ok"})
        self.assertTrue(result["isError"])
        self.assertIn("approval_id=a1", result["content"][0]["text"])
        self.assertEqual(module._original.call_count, 0)

    def test_guarded_server_approved_exact_request_executes_once(self):
        module = self._guarded_module()
        ask = types.SimpleNamespace(allowed=False, requires_approval=True)
        params = {"cmd": "echo ok"}
        with patch.object(module, "check", return_value=ask), patch.object(module, "authorize_command", return_value=(True, "")), patch.object(module, "consume_approval_bundle") as consume, patch.object(module, "record"):
            consume.return_value = {"approval_id": "a1"}
            result = module.guarded_call(None, "run", {**params, "approval_id": "a1"})
        self.assertEqual(result, {"ok": True})
        consume.assert_called_once_with("a1", ["mcp.execute", "terminal.run"], "run", params)
        self.assertEqual(module._original.call_count, 1)

    def test_approval_bundle_is_atomic_and_one_shot(self):
        p = policy.load_policy()
        p["master_lock"] = False
        policy.set_state(p, "terminal.run", "ask")
        policy.set_state(p, "mcp.execute", "ask")
        policy.save_policy(p)

        params = {"cmd": "echo ok"}
        item = approval.create_bundle(["terminal.run", "mcp.execute"], "run", params)
        approval.approve(item["approval_id"])
        approval.consume_bundle(item["approval_id"], ["terminal.run", "mcp.execute"], "run", params)
        with self.assertRaisesRegex(PermissionError, "approval_already_consumed"):
            approval.consume_bundle(item["approval_id"], ["terminal.run", "mcp.execute"], "run", params)

    def test_guarded_server_tampered_approval_never_executes(self):
        module = self._guarded_module()
        ask = types.SimpleNamespace(allowed=False, requires_approval=True)
        with patch.object(module, "check", return_value=ask), patch.object(module, "authorize_command", return_value=(True, "")), patch.object(module, "consume_approval_bundle", side_effect=PermissionError("approval_request_mismatch")), patch.object(module, "record"):
            result = module.guarded_call(None, "run", {"cmd": "echo tampered", "approval_id": "a1"})
        self.assertTrue(result["isError"])
        self.assertIn("approval_request_mismatch", result["content"][0]["text"])
        self.assertEqual(module._original.call_count, 0)

    def test_guarded_server_blocks_if_secondary_capability_denied(self):
        module = self._guarded_module()
        allow = types.SimpleNamespace(allowed=True, requires_approval=False)
        deny = types.SimpleNamespace(allowed=False, requires_approval=False)
        def decision(capability):
            return deny if capability == "mcp.execute" else allow
        with patch.object(module, "check", side_effect=decision), patch.object(module, "authorize_command", return_value=(True, "")), patch.object(module, "record"):
            result = module.guarded_call(None, "run", {"cmd": "echo ok"})
        self.assertTrue(result["isError"])
        self.assertIn("mcp.execute", result["content"][0]["text"])
        self.assertEqual(module._original.call_count, 0)

    def test_guarded_server_command_guard_runs_before_execution(self):
        module = self._guarded_module()
        with patch.object(module, "authorize_command", return_value=(False, "blocked")), patch.object(module, "check") as check, patch.object(module, "record"):
            result = module.guarded_call(None, "run", {"cmd": "rm -rf x"})
        self.assertTrue(result["isError"])
        self.assertIn("command denied: blocked", result["content"][0]["text"])
        check.assert_not_called()
        self.assertEqual(module._original.call_count, 0)

    def test_each_control_area_has_concrete_mapped_actions(self):
        from core.capability_map import AREA_TOOL_ACTIONS, capability_for_tool
        canonical = Path(__file__).resolve().parents[1] / "policies" / "default.json"
        policy_data = json.loads(canonical.read_text(encoding="utf-8"))
        ids = {item["id"] for group in policy_data["capabilities"].values() for item in group}
        expected = {"files","git","terminal","network","mcp","device","dangerous"}
        self.assertEqual(set(AREA_TOOL_ACTIONS), expected)
        for area, tools in AREA_TOOL_ACTIONS.items():
            self.assertTrue(tools, area)
            for tool in tools:
                cap = capability_for_tool(tool)
                self.assertIn(cap, ids, f"{area}:{tool}->{cap}")

    def test_area_catalog_covers_every_mapped_tool(self):
        catalog_tools = {tool for tools in AREA_TOOL_ACTIONS.values() for tool in tools}
        self.assertEqual(catalog_tools, set(TOOL_CAPABILITIES))

    def test_policy_capabilities_are_operational_or_explicitly_reserved(self):
        canonical = Path(__file__).resolve().parents[1] / "policies" / "default.json"
        policy_data = json.loads(canonical.read_text(encoding="utf-8"))
        ids = {item["id"] for group in policy_data["capabilities"].values() for item in group}
        operational = set(TOOL_CAPABILITIES.values())
        operational.update(cap for caps in TOOL_SECONDARY_CAPABILITIES.values() for cap in caps)
        # Unknown tools fail closed through this explicit fallback capability.
        operational.add("dangerous.outside_allowlist")
        operational.update({
            "git.pull", "git.status", "git.diff", "git.add", "git.commit",
            "git.push", "git.branch",
            "files.repo", "files.control", "files.repo_data", "files.repo_tools",
            "files.repo_reports", "files.repo_tmp", "files.tunnel_install",
            "files.home", "files.shared_storage", "files.custom",
        })
        reserved = CONTROL_ONLY_CAPABILITIES
        self.assertEqual(operational | reserved, ids)
        self.assertTrue(operational.isdisjoint(reserved))

    def test_explicit_tool_mapping_is_covered_by_policy(self):
        canonical = Path(__file__).resolve().parents[1] / "policies" / "default.json"
        policy_data = json.loads(canonical.read_text(encoding="utf-8"))
        ids = {item["id"] for group in policy_data["capabilities"].values() for item in group}
        operational = set(TOOL_CAPABILITIES.values())
        operational.update(cap for caps in TOOL_SECONDARY_CAPABILITIES.values() for cap in caps)
        self.assertTrue(operational <= ids)
        self.assertEqual(capability_for_tool("not-a-real-tool"), "dangerous.outside_allowlist")
        expected_git = {
            "git pull --rebase origin main": "git.pull",
            "git status --short": "git.status",
            "git diff --check": "git.diff",
            "git add core/policy.py": "git.add",
            "git commit -m test": "git.commit",
            "git push origin main": "git.push",
            "git switch main": "git.branch",
            "git checkout main": "git.branch",
            "git branch --show-current": "git.branch",
        }
        for command, capability in expected_git.items():
            self.assertEqual(git_capability_for_command(command), capability)
        self.assertIsNone(git_capability_for_command("echo git status"))


if __name__ == "__main__":
    unittest.main()
