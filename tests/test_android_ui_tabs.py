from pathlib import Path
import unittest


class AndroidUiTabTests(unittest.TestCase):
    def test_capability_tabs_use_canonical_policy_path_and_shared_renderer(self):
        source = Path(__file__).parents[1] / "android/app/src/main/java/com/re3ae6/mcpcontrol/MainActivity.java"
        text = source.read_text(encoding="utf-8")
        self.assertIn('private JSONArray capabilitiesForGroup(String name)', text)
        self.assertIn('JSONObject caps = policy.optJSONObject("capabilities");', text)
        self.assertIn('return caps == null ? null : caps.optJSONArray(name);', text)
        self.assertIn('JSONArray a = capabilitiesForGroup(group);', text)
        self.assertIn('addCapabilityRow(x, locked);', text)
        self.assertNotIn('policy.optJSONObject(group)', text)
