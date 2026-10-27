"""implementations.py: which checks run for a tools root, which are skipped and why, and what's compared.

python3 -m unittest discover -s tools/reference/tests
"""
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import implementations  # noqa: E402


class Plan(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        for name in ("droop", "schulze3", "STVPoll-venv/bin"):
            os.makedirs(os.path.join(self.root.name, name))
        for name in ("A01", "A13", "ppb2011", "pps2014"):
            pathlib.Path(self.root.name, "schulze3", name + "_list.dat").touch()
        pathlib.Path(self.root.name, "STVPoll-venv", "bin", "python").touch()

    def tearDown(self):
        self.root.cleanup()

    def plan(self, **options):
        options.setdefault("python2", sys.executable)   # a Python that runs, and quickly (pyenv's shim isn't)
        return {name: (command, skip) for name, command, skip in implementations.steps(self.root.name, "/work",
                                                                                       **options)}

    def test_a_check_runs_where_its_tool_is(self):
        plan = self.plan()
        self.assertIsNone(plan["droop"][1])
        self.assertIsNone(plan["stvpoll"][1])
        self.assertIn("opentally", plan["opentally-act"][1])
        self.assertIn("singl01p", plan["schulze-stv"][1])

    def test_a_check_needing_two_tools_is_skipped_without_either(self):
        self.assertIn("openstv", self.plan()["qpq"][1])

    def test_openstv_s_checks_need_a_python2_that_runs(self):
        os.makedirs(os.path.join(self.root.name, "openstv"))
        plan = self.plan(python2=os.path.join(self.root.name, "no-python2"))
        self.assertIn("doesn't run", plan["openstv-borda"][1])
        self.assertIn("doesn't run", plan["qpq"][1])
        self.assertIsNone(self.plan(python2=sys.executable)["openstv-borda"][1])

    def test_a_virtual_environment_s_python_runs_its_check(self):
        self.assertEqual(self.plan()["stvpoll"][0][0], os.path.join(self.root.name, "STVPoll-venv", "bin", "python"))

    def test_the_slow_lists_are_left_out(self):
        command = self.plan()["schulze-proportional"][0]
        self.assertIn("A01", command)
        self.assertIn("ppb2011", command)
        self.assertNotIn("A13", command)
        self.assertNotIn("pps2014", command)

    def test_only_by_name_or_prefix(self):
        self.assertEqual(sorted(self.plan(only=["droop"])), ["droop"])
        self.assertEqual(sorted(self.plan(only=["pyrcv2"])),
                         ["pyrcv2-ers97", "pyrcv2-gregory", "pyrcv2-scottish", "pyrcv2-senate"])
        self.assertEqual(len(self.plan(only=["opentally"])), len(implementations.OPENTALLY_PRESETS))
        self.assertEqual(sorted(self.plan(only=["openstv-borda"])), ["openstv-borda"])


class Compare(unittest.TestCase):

    def test_indented_and_blank_lines_aren_t_reported(self):
        output = "e5: 9/10 rounds match\n      round 7: loss 1 against 2\n\nact: 199 of 200 match\n"
        self.assertEqual(implementations.reported(output), ["e5: 9/10 rounds match", "act: 199 of 200 match"])

    def test_changes_list_lines_gone_and_new(self):
        self.assertEqual(implementations.changes(["e5: differs", "act: 199 of 200"], ["act: 200 of 200"]),
                         ["  - e5: differs", "  - act: 199 of 200", "  + act: 200 of 200"])
        self.assertEqual(implementations.changes(["act: 200 of 200"], ["act: 200 of 200"]), [])


if __name__ == "__main__":
    unittest.main()
