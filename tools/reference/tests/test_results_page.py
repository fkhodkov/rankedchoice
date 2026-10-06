"""The results page: what a checker records of each contest, the figures the page shows, and its two safeguards, an
unexplained difference shown and reported, and a difference naming a contest that now matches reported.

python3 -m unittest discover -s tools/reference/tests
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import rcvcheck  # noqa: E402
import results_page  # noqa: E402


def contest(name, matched, rounds, same=True):
    return {"contest": name, "ok": True, "matched": matched, "rounds": rounds, "winners_same": same, "line": name}


DATA = {
    "groups": [{"name": "One seat", "single_seat": True, "jurisdictions": [
        {"name": "Somewhere", "years": "2024", "ruleset": "irv", "checker": "somewhere", "against": "Reports"}]}],
    "causes": [{"id": "record", "title": "The record differs", "why": "A few votes."}],
    "differences": [{"cause": "record", "checker": "somewhere", "contests": ["b", "c"], "text": "Somewhere: b and c."}],
    "example": {"title": "Example", "intro": "Intro.", "our_rounds": 2,
                "rounds": [{"official": 1, "ours": 0, "label": "Round 1", "text": "First.", "figures": "A 1"}]},
    "rules": {"columns": ["Counts", "Options"], "rows": [["Somewhere", "--skipped two-end"], ["Elsewhere", None]]},
}
CHECKED = {"somewhere": {"date": "2026-10-05", "contests": [
    contest("a", 3, 3), contest("b", 2, 3), contest("c", 4, 4), contest("d", 1, 2), contest("e", 2, 2, same=False)]}}


class Record(unittest.TestCase):

    def test_rounds_and_winners_read_off_the_lines(self):
        self.assertEqual(rcvcheck.record("x", True, "x: 3 of 4 official rounds match to the vote; eliminations the "
                                                    "same; elected the same"),
                         {"contest": "x", "ok": True, "matched": 3, "rounds": 4, "winners_same": True,
                          "line": "x: 3 of 4 official rounds match to the vote; eliminations the same; elected the same"})
        multi = rcvcheck.record("1", True, "District 1: quota 1 (official 1); 15 of 16 official rounds match a round "
                                           "of this count; elected the same")
        self.assertEqual((multi["matched"], multi["rounds"]), (15, 16))
        passes = rcvcheck.record("p", False, "p: 1 of 2 passes match to the vote; elected DIFFERENTLY: ['B']")
        self.assertEqual((passes["matched"], passes["winners_same"]), (1, False))

    def test_winners_only(self):
        only = rcvcheck.record("w", True, "w: winners only; by the 2006 rules, the same")
        self.assertEqual((only["matched"], only["rounds"], only["winners_same"]), (None, None, True))


class Page(unittest.TestCase):

    def test_figures(self):
        f = results_page.figures(DATA["groups"][0]["jurisdictions"][0], CHECKED, DATA)
        self.assertEqual((f["contests"], f["every_round"], f["with_rounds"], f["winners_same"], f["short"]),
                         (5, 3, 5, 4, 2))

    def test_figures_filled_into_text(self):
        self.assertEqual(results_page.fill("{somewhere.every_round} of {somewhere.contests}", CHECKED, DATA), "3 of 5")

    def test_a_difference_covering_all_of_a_checker(self):
        data = dict(DATA, differences=[{"cause": "record", "checker": "somewhere", "contests": "all", "text": "All."}])
        self.assertEqual(results_page.build(data, CHECKED, "", "")[1], [])

    def test_winners_confirmed_by_hand(self):
        data = dict(DATA, differences=DATA["differences"] + [
            {"cause": "record", "checker": "somewhere", "contests": ["e"], "winners_confirmed": True, "text": "e."}])
        self.assertEqual(results_page.figures(data["groups"][0]["jurisdictions"][0], CHECKED, data)["winners_same"], 5)

    def test_unexplained_differences_shown_and_reported(self):
        page, unexplained, warnings = results_page.build(DATA, CHECKED, "", "Stamp.")
        self.assertEqual(unexplained, ["somewhere d: d", "somewhere e: e"])
        self.assertIn("Not yet explained", page)
        self.assertIn("1 contest elects differently", page)
        self.assertEqual(warnings, ["somewhere c is named as a difference but now matches every round"])

    def test_a_difference_naming_no_contest_of_the_checker(self):
        data = dict(DATA, differences=[{"cause": "record", "checker": "somewhere", "contests": ["zz"], "text": "?"}])
        self.assertIn("somewhere zz is named as a difference but isn't among the checker's contests",
                      results_page.build(data, CHECKED, "", "")[2])

    def test_rules_options(self):
        rows = results_page.rules(DATA)
        self.assertIn("<code>--skipped two-end</code>", rows)
        self.assertIn("its own converter", rows)


class ValidationTable(unittest.TestCase):

    def test_validation_md_matches_the_results(self):
        """VALIDATION.md's Official counts table is what the committed results give (results_page.py --validation
        write rewrites it)."""
        data, checked = results_page.load()
        with open(results_page.VALIDATION, encoding="utf-8") as source:
            text = source.read()
        self.assertEqual(results_page.with_table(text, results_page.validation_table(data, checked)), text)

    def test_a_stale_table_is_told_apart(self):
        data = dict(DATA, validation_table={"columns": ["Jurisdiction", "Reproduced"],
                                            "rows": [["Somewhere", "every round in {somewhere.every_round}"]]})
        table = results_page.validation_table(data, CHECKED)
        self.assertEqual(table, "| Jurisdiction | Reproduced |\n|---|---|\n| Somewhere | every round in 3 |\n\n"
                                "From the checks last run on 2026-10-05 (`tools/reference/refresh_results.py`).")
        stale = f"Before\n{results_page.BEGIN}\n| Somewhere | every round in 5 |\n{results_page.END}\nAfter"
        self.assertNotEqual(results_page.with_table(stale, table), stale)
        self.assertIn("every round in 3", results_page.with_table(stale, table))


if __name__ == "__main__":
    unittest.main()
