"""The Maine checker's reading of where a ballot is, as its 2018-2020 reports count exhausted ballots: by overvotes,
by undervotes (two skipped rankings in a row, or running out after skipping any), or by exhausted choices.

python3 -m unittest discover -s tools/reference/tests
"""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("maine_check", os.path.join(HERE, "..", "maine", "check.py"))
maine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(maine)
where = maine.where


class Where(unittest.TestCase):

    def test_the_first_continuing_candidate(self):
        self.assertEqual(where(["Ann", "Bob"], set()), "ANN")
        self.assertEqual(where(["Ann", "skipped", "Bob"], {"ANN"}), "BOB")

    def test_a_write_in_is_one_candidate(self):
        self.assertEqual(where(["Write-in", "Bob"], set()), "WRITEIN")
        self.assertEqual(where(["Write-in", "Bob"], {"WRITEIN"}), "BOB")

    def test_two_skips_in_a_row_or_an_overvote_end_it(self):
        self.assertEqual(where(["Ann", "skipped", "skipped", "Bob"], {"ANN"}), "undervotes")
        self.assertEqual(where(["Ann", "overvote", "Bob"], {"ANN"}), "overvotes")
        self.assertEqual(where(["skipped", "skipped", "skipped"], set()), "undervotes")

    def test_running_out_is_exhausted_choices_only_without_a_skip(self):
        self.assertEqual(where(["Ann", "Bob", "Cy"], {"ANN", "BOB", "CY"}), "exhausted choices")
        self.assertEqual(where(["Ann", "skipped", "Bob"], {"ANN", "BOB"}), "undervotes")
        self.assertEqual(where(["Ann", "Bob", "skipped"], {"ANN", "BOB"}), "undervotes")


if __name__ == "__main__":
    unittest.main()
