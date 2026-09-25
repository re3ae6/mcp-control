import unittest
from core.command_guard import authorize_command

class CommandGuardRmTests(unittest.TestCase):
    def test_rm_workspace_absolute_allowed(self):
        ok, reason = authorize_command("rm -f ~/po_recorder/data/test-junk.jsonl")
        self.assertTrue(ok, reason)

    def test_rm_workspace_multiple_allowed(self):
        ok, reason = authorize_command("rm ~/po_recorder/a ~/po_recorder/b")
        self.assertTrue(ok, reason)

    def test_rm_outside_denied(self):
        ok, _ = authorize_command("rm -f ~/mcp-control/file.txt")
        self.assertFalse(ok)

    def test_rm_absolute_outside_denied(self):
        ok, _ = authorize_command("rm -f /sdcard/file.txt")
        self.assertFalse(ok)

    def test_rm_bare_target_denied(self):
        ok, _ = authorize_command("rm file.txt")
        self.assertFalse(ok)

    def test_rm_recursive_still_denied(self):
        ok, _ = authorize_command("rm -rf ~/po_recorder/tmp")
        self.assertFalse(ok)

    def test_rm_traversal_denied(self):
        ok, _ = authorize_command("rm -f ~/po_recorder/../mcp-control/file.txt")
        self.assertFalse(ok)

if __name__ == "__main__":
    unittest.main()
