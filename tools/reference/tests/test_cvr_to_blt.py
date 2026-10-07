"""The ballot-reading rules of tools/reference/cvr_to_blt.py, one option at a time, and its file handling.

python3 -m unittest discover -s tools/reference/tests
"""
import argparse
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
sys.path.insert(0, os.path.join(HERE, ".."))
import cvr_to_blt  # noqa: E402


def read(*marks, overvote="end", skipped="pass", repeat="once", write_in="pass", write_in_name="Write-in"):
    """The ranking a ballot's marks give, as candidate names."""
    args = argparse.Namespace(overvote=overvote, skipped=skipped, repeat=repeat, write_in=write_in,
                              write_in_name=write_in_name)
    candidates = []
    return [candidates[i] for i in cvr_to_blt.ranking_of(list(marks), args, candidates)]


class Rules(unittest.TestCase):

    def test_defaults_pass_over_skips_and_write_ins_and_end_at_an_overvote(self):
        self.assertEqual(read("A", "skipped", "", "undervote", "B"), ["A", "B"])
        self.assertEqual(read("Write-in", "A", "writein", "B"), ["A", "B"])
        self.assertEqual(read("A", "overvote", "B"), ["A"])
        self.assertEqual(read("overvote", "A"), [])

    def test_overvote_skip_passes_over_it(self):
        self.assertEqual(read("A", "overvote", "B", overvote="skip"), ["A", "B"])

    def test_two_skips_in_a_row_end_the_ballot_those_before_the_first_choice_too(self):
        self.assertEqual(read("A", "skipped", "B", skipped="two-end"), ["A", "B"])
        self.assertEqual(read("A", "skipped", "skipped", "B", skipped="two-end"), ["A"])
        self.assertEqual(read("skipped", "skipped", "A", skipped="two-end"), [])
        self.assertEqual(read("skipped", "A", "skipped", "B", skipped="two-end"), ["A", "B"])

    def test_a_write_in_passed_over_neither_counts_as_a_skip_nor_breaks_a_run_of_them(self):
        self.assertEqual(read("A", "Write-in", "skipped", "B", skipped="two-end"), ["A", "B"])
        self.assertEqual(read("A", "skipped", "Write-in", "skipped", "B", skipped="two-end"), ["A"])

    def test_a_write_in_counted_as_a_skip(self):
        self.assertEqual(read("A", "writein", "skipped", "B", skipped="two-end", write_in="skip"), ["A"])
        self.assertEqual(read("A", "writein", "B", skipped="two-end", write_in="skip"), ["A", "B"])

    def test_a_write_in_as_one_candidate(self):
        self.assertEqual(read("writein", "A", "Write in", write_in="candidate"), ["Write-in", "A"])
        self.assertEqual(read("UWI", "A", write_in="candidate", write_in_name="Undeclared Write-ins"),
                         ["Undeclared Write-ins", "A"])

    def test_a_candidate_ranked_again_counts_once_or_ends_the_ballot(self):
        self.assertEqual(read("A", "A", "B"), ["A", "B"])
        self.assertEqual(read("A", "B", "A", "C"), ["A", "B", "C"])
        self.assertEqual(read("A", "A", "B", repeat="end"), ["A"])

    def test_a_repeat_breaks_a_run_of_skips(self):
        self.assertEqual(read("A", "skipped", "A", "skipped", "B", skipped="two-end"), ["A", "B"])

    def test_a_repeat_can_count_as_a_skipped_ranking(self):
        # Alaska's 2022 US House count: Bye four times, then Peltola, is exhausted once Bye is out.
        self.assertEqual(read("A", "A", "A", "A", "B", skipped="two-end", repeat="skip"), ["A"])
        self.assertEqual(read("A", "A", "skipped", "B", skipped="two-end", repeat="skip"), ["A"])
        self.assertEqual(read("A", "A", "B", "skipped", "C", skipped="two-end", repeat="skip"), ["A", "B", "C"])
        self.assertEqual(read("A", "A", "B", repeat="skip"), ["A", "B"])
        # Its 2022 US Senate count: three write-ins, then Kelley, is exhausted.
        self.assertEqual(read("Write-in", "Write-in", "Write-in", "B", skipped="two-end", repeat="skip"), [])
        self.assertEqual(read("Write-in", "skipped", "B", skipped="two-end", repeat="skip"), ["B"])


class Files(unittest.TestCase):

    def convert(self, text, *options, name="cvr.csv"):
        with tempfile.TemporaryDirectory() as directory:
            cvr, blt = os.path.join(directory, name), os.path.join(directory, "out.blt")
            with open(cvr, "w", encoding="utf-8") as out:
                out.write(text)
            subprocess.run([sys.executable, CONVERTER, cvr, blt, *options], check=True)
            with open(blt, encoding="utf-8") as converted:
                return converted.read()

    def test_rankings_merged_most_ballots_first_candidates_numbered_as_they_appear(self):
        blt = self.convert("ballot,rank1,rank2\n1,B,A\n2,A,skipped\n3,B,A\n4,skipped,skipped\n",
                           "--seats", "2", "--title", "Test")
        self.assertEqual(blt, '2 2\n2 1 2 0\n1 2 0\n0\n"B"\n"A"\n"Test"\n')

    def test_rank_columns_in_numeric_order_and_tabs_detected(self):
        header = "\t".join(["id"] + [f"rank{n}" for n in (10, 1, 2, 3, 4, 5, 6, 7, 8, 9)])
        row = "\t".join(["1", "J"] + list("ABCDEFGHI"))
        blt = self.convert(header + "\n" + row + "\n")
        self.assertIn("1 1 2 3 4 5 6 7 8 9 10 0\n", blt)
        self.assertTrue(blt.endswith('"I"\n"J"\n"Contest"\n'))

    def test_choice_columns_and_a_count_of_identical_ballots(self):
        blt = self.convert("Precinct,1st Choice,2nd Choice,3rd Choice,Count\n"
                           "P1,A,overvote,B,5\nP2,UWI,A,undervote,2\n",
                           "--overvote", "skip", "--write-in", "candidate", "--write-in-name", "Undeclared Write-ins")
        self.assertEqual(blt, '3 1\n5 1 2 0\n2 3 1 0\n0\n"A"\n"B"\n"Undeclared Write-ins"\n"Contest"\n')


if __name__ == "__main__":
    unittest.main()
