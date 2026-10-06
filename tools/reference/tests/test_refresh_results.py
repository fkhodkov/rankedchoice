"""refresh_results.py's plan: which checkers run for a data root, which are skipped and why, and what follows them.

python3 -m unittest discover -s tools/reference/tests
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import refresh_results  # noqa: E402


class Plan(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        for name in ("maine", "albany", "scotland/scot-elex"):
            os.makedirs(os.path.join(self.root.name, name))
        open(os.path.join(self.root.name, "scotland", "official.json"), "w").close()

    def tearDown(self):
        self.root.cleanup()

    def plan(self, **options):
        return {name: (command, skip) for name, command, skip in refresh_results.steps(self.root.name, **options)}

    def test_a_checker_runs_where_its_directory_is(self):
        plan = self.plan()
        self.assertIsNone(plan["maine"][1])
        self.assertEqual(plan["maine"][0][-2:], ["--results", os.path.join(refresh_results.RESULTS, "maine.json")])
        self.assertIn("no ", plan["nyc"][1])

    def test_scotland_needs_each_of_its_inputs(self):
        plan = self.plan()
        self.assertIsNone(plan["scotland"][1])
        self.assertIn("official-stages.json", plan["scotland-wikipedia"][1])
        self.assertIn("reports", plan["scotland-stage-reports"][1])

    def test_the_fixture_tests_page_and_table_come_last(self):
        names = [name for name, _, _ in refresh_results.steps(self.root.name)]
        self.assertEqual(names[-3:], ["act, ireland", "results page", "VALIDATION.md table"])
        self.assertEqual(self.plan()["VALIDATION.md table"][0][-2:], ["--validation", "write"])

    def test_only_some_checkers_and_no_fixture_tests(self):
        names = [name for name, _, _ in refresh_results.steps(self.root.name, only=["maine"])]
        self.assertEqual(names, ["maine", "results page", "VALIDATION.md table"])
        self.assertNotIn("act, ireland", [name for name, _, _ in refresh_results.steps(self.root.name, java=False)])


if __name__ == "__main__":
    unittest.main()
