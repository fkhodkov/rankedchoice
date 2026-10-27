"""refresh_results.py's plan: which checkers run for a data root, which are skipped and why, and what follows them.

python3 -m unittest discover -s tools/reference/tests
"""
import os
import pathlib
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
        pathlib.Path(self.root.name, "scotland", "official.json").touch()

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

    def test_compare_writes_elsewhere_and_rebuilds_nothing(self):
        plan = refresh_results.steps(self.root.name, results_dir="/elsewhere", page=False)
        names = [name for name, _, _ in plan]
        self.assertNotIn("results page", names)
        self.assertNotIn("VALIDATION.md table", names)
        self.assertEqual(dict((n, c) for n, c, _ in plan)["maine"][-1], "/elsewhere/maine.json")
        self.assertIn("-Dresults.dir=/elsewhere", dict((n, c) for n, c, _ in plan)["act, ireland"])


class Compare(unittest.TestCase):

    def test_the_copy_leaves_out_the_cached_blt_files(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as work:
            os.makedirs(os.path.join(root, "maine"))
            for name in ("a.csv", "a.blt"):
                pathlib.Path(root, "maine", name).touch()
            with open(os.path.join(root, "maine", "a.pdf"), "w") as out:
                out.write("x" * refresh_results.SMALL)
            pathlib.Path(root, "notes.txt").touch()
            refresh_results.shadow(root, os.path.join(work, "copy"))
            self.assertEqual(sorted(os.listdir(os.path.join(work, "copy"))), ["maine"])
            self.assertEqual(sorted(os.listdir(os.path.join(work, "copy", "maine"))), ["a.csv", "a.pdf"])
            self.assertTrue(os.path.islink(os.path.join(work, "copy", "maine", "a.pdf")))

    def test_small_files_are_copied_so_rewriting_one_leaves_the_data_alone(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as work:
            os.makedirs(os.path.join(root, "sanfrancisco"))
            with open(os.path.join(root, "sanfrancisco", "a.capture"), "w") as out:
                out.write("old\n")
            with open(os.path.join(root, "sanfrancisco", "a.csv"), "w") as out:
                out.write("x" * refresh_results.SMALL)
            refresh_results.shadow(root, os.path.join(work, "copy"))
            note = os.path.join(work, "copy", "sanfrancisco", "a.capture")
            self.assertFalse(os.path.islink(note))
            with open(note, "w") as out:
                out.write("new\n")
            self.assertEqual(pathlib.Path(root, "sanfrancisco", "a.capture").read_text(), "old\n")
            self.assertTrue(os.path.islink(os.path.join(work, "copy", "sanfrancisco", "a.csv")))

    def test_results_differ_contest_by_contest_the_date_aside(self):
        def results(*contests, date="2026-10-01"):
            return {"checker": "x", "date": date, "contests": [
                {"contest": c, "ok": True, "line": f"{c}: {line}"} for c, line in contests]}
        committed = results(("a", "2 of 2"), ("b", "1 of 1"))
        self.assertEqual(refresh_results.differences(committed, results(("a", "2 of 2"), ("b", "1 of 1"),
                                                                         date="2026-10-07")), [])
        lines = refresh_results.differences(committed, results(("a", "1 of 2"), ("c", "3 of 3")))
        self.assertEqual(lines, ["  a: line changed", "    was: a: 2 of 2", "    now: a: 1 of 2",
                                 "  b: no longer checked (was: b: 1 of 1)", "  c: new: c: 3 of 3"])


if __name__ == "__main__":
    unittest.main()
