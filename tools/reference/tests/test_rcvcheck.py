"""tools/reference/rcvcheck.py: name keys, matching official rounds with this count's, the summary lines, and the
pass-report parser, on small made-up counts.

python3 -m unittest discover -s tools/reference/tests
"""
import os
import sys
import unittest
from decimal import Decimal

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import rcvcheck  # noqa: E402
from rcvcheck import name_key  # noqa: E402


def ours(*rounds, elected=(), quota="0"):
    """A count's JSON report, as far as the checkers read it: each round's tallies, as strings."""
    return {"rounds": [{"tallies": {name: str(votes) for name, votes in tallies.items()}} for tallies in rounds],
            "electedInOrder": list(elected), "quota": quota}


# A count of five, one defeated a round: A wins after D, then C, then B go.
COUNT = ours({"A": 40, "B": 30, "C": 20, "D": 10},
             {"A": 45, "B": 33, "C": 22},
             {"A": 55, "B": 45},
             {"A": 100}, elected=["A"])


class NameKeys(unittest.TestCase):

    def test_letters_only_accents_dropped(self):
        self.assertEqual(name_key("Robin D. López"), name_key("ROBIN D  LOPEZ"))
        self.assertEqual(name_key("ANDREW ''ELLARD'' RESIGNATO"), name_key("Andrew 'Ellard' Resignato"))

    def test_initials_left_out_on_request(self):
        self.assertNotEqual(name_key("Selvena N. Brooks-Powers"), name_key("Selvena Brooks-Powers"))
        self.assertEqual(name_key("Selvena N. Brooks-Powers", initials=False),
                         name_key("Selvena Brooks-Powers", initials=False))

    def test_write_ins_folded_into_one_only_on_request(self):
        self.assertNotEqual(name_key("WRITE-IN RODNEY HAUGE"), name_key("WRITE-IN LEA SHERMAN"))
        self.assertEqual(name_key("Write-ins", one_write_in=True), name_key("WRITE-IN", one_write_in=True))
        self.assertEqual(name_key("WRITE-IN RODNEY HAUGE", one_write_in=True), "WRITEIN")


class MatchRounds(unittest.TestCase):

    def test_one_a_round_is_round_for_round(self):
        official = {"A": [40, 45, 55], "B": [30, 33, 45], "C": [20, 22, 0], "D": [10, 0, 0]}
        self.assertEqual(rcvcheck.match_rounds(official, COUNT, name_key), (3, [0, 0, 0]))

    def test_several_defeated_at_once_and_a_round_that_changes_nothing(self):
        # D and C go together (this count's rounds 2 and 3 in one); round 2 is printed twice.
        official = {"A": [40, 55, 55], "B": [30, 45, 45], "C": [20], "D": [10]}
        self.assertEqual(rcvcheck.match_rounds(official, COUNT, name_key), (3, [0, 0, 0]))

    def test_differences_reported_and_a_round_with_other_candidates_not_found(self):
        # Round 2 keeps C but not B: no round of this count has those two left.
        official = {"A": [41, 55], "B": [30], "C": [20, 1], "D": [10]}
        self.assertEqual(rcvcheck.match_rounds(official, COUNT, name_key), (0, [1, None]))

    def test_rounds_must_come_in_order(self):
        official = {"A": [55, 40], "B": [45, 30], "C": [0, 20], "D": [0, 10]}
        self.assertEqual(rcvcheck.match_rounds(official, COUNT, name_key)[1], [0, None])

    def test_from_a_later_round(self):
        official = {"A": [999, 55], "B": [999, 45], "W": [5]}
        self.assertEqual(rcvcheck.match_rounds(official, COUNT, name_key, first=1), (1, [0]))

    def test_winner_has_most_votes_in_the_last_round(self):
        self.assertEqual(rcvcheck.winner({"A": [40, 55], "B": [30, 45], "C": [20]}), "A")


class UnmatchedRounds(unittest.TestCase):

    def test_to_the_official_decimal_places_in_order(self):
        count = ours({"A": "10.5000", "B": "7.2500"}, {"A": "12.7500"})
        official = [(1, {"A": Decimal("10.5"), "B": Decimal("7.25")}), (2, {"A": Decimal("12.75")})]
        self.assertEqual(rcvcheck.unmatched_rounds(official, count), [])
        official[1] = (2, {"A": Decimal("12.7501")})
        self.assertEqual(rcvcheck.unmatched_rounds(official, count), [2])

    def test_names_found_through_a_lookup(self):
        count = ours({"Robin López": "3"})
        official = [(1, {name_key("ROBIN LOPEZ"): Decimal(3)})]
        self.assertEqual(rcvcheck.unmatched_rounds(official, count), [1])
        self.assertEqual(rcvcheck.unmatched_rounds(official, count, lambda f, name: f.get(name_key(name))), [])


class Lines(unittest.TestCase):

    def test_a_contest_that_checks_out(self):
        self.assertEqual(rcvcheck.summary("x", 2, [0, 0], True, ["A"]),
                         "x: 2 of 2 official rounds match to the vote; eliminations the same; elected the same")

    def test_differences_and_where_the_rounds_part(self):
        self.assertEqual(rcvcheck.summary("x", 1, [0, 3, None], False, ["B"]),
                         "x: 1 of 3 official rounds match to the vote (largest differences by round: [0, 3, None]); "
                         "ELIMINATIONS DIFFER from official round 3; elected DIFFERENTLY: ['B']")

    def test_passes_numbered_from_0(self):
        self.assertEqual(rcvcheck.summary("x", 1, [0, None], True, ["A"], rounds="passes", round_name="pass",
                                          first=0),
                         "x: 1 of 2 passes match to the vote; ELIMINATIONS DIFFER from pass 1; elected the same")

    def test_threshold_line(self):
        self.assertEqual(rcvcheck.threshold_line("x", ours(quota="2387.000000"), "2387", 4, 4, True, []),
                         "x: threshold 2387.000000 (official 2387); 4 of 4 official rounds match a round of this "
                         "count; elected the same")


PASS_REPORT = """\
Run Id: 1 - Pass 0                       Pass Number: 0
Final State
   Candidate                                     Votes        % Vote
   ANNE SMITH                                    600          60.00%
   BOB WRITE-A-VERY-LONG-NAME-THAT-              300          30.00%
   WRAPS
   Write-In                                      100          10.00%
                                  Continuing Ballots          1000
Run Id: 1 - Pass 1                       Pass Number: 1
Initial State
   Candidate                                     Votes        % Vote
   ANNE SMITH                                    600          60.00%
Final State
   Candidate                                     Votes        % Vote
   ANNE SMITH          ** WINNER **              650          65.00%
   BOB WRITE-A-VERY-LONG-NAME-THAT-              350          35.00%
   WRAPS
   Write-In            Eliminated in pass 1.     0            0.00%
                                  Continuing Ballots          1000
"""


def eliminations(*rounds):
    """A count's JSON report with each round's exhausted votes and its elimination: (tallies, exhausted, eliminated)."""
    return {"rounds": [{"tallies": {name: str(votes) for name, votes in tallies.items()}, "exhaustedVotes": str(gone),
                        "action": {"type": "eliminated", "candidate": loser}} for tallies, gone, loser in rounds],
            "electedInOrder": []}


# C goes first, 10 ballots: 6 to A, 3 to B, 1 exhausted; then B, 33: 20 to A, 13 exhausted.
TRANSFERS = eliminations(({"A": 50, "B": 30, "C": 10}, 0, "C"), ({"A": 56, "B": 33}, 1, "B"), ({"A": 76}, 14, "A"))


class Transfers(unittest.TestCase):

    def test_each_elimination_from_the_change_between_rounds(self):
        self.assertEqual(rcvcheck.transfers(TRANSFERS), {"C": {"A": 6, "B": 3, "Exhausted": 1},
                                                         "B": {"A": 20, "Exhausted": 13}})

    def test_the_same_with_exhausted_and_overvotes_together(self):
        official = [("C", {"A": 6, "B": 3, "Exhausted": 0, "Overvotes": 1}), ("B", {"A": 20, "Exhausted": 13})]
        self.assertEqual(rcvcheck.transfer_line(official, TRANSFERS, name_key),
                         "; every transfer the official one (2 eliminations)")

    def test_ballots_the_record_lacks_are_off_one_way(self):
        official = [("C", {"A": 7, "B": 3, "Exhausted": 1}), ("B", {"A": 20, "Exhausted": 14})]
        self.assertEqual(rcvcheck.transfer_line(official, TRANSFERS, name_key),
                         "; transfers 2 ballots short in 2 eliminations, none moved between destinations")

    def test_a_ballot_read_otherwise_moves_between_destinations(self):
        official = [("C", {"A": 5, "B": 3, "Exhausted": 2}), ("B", {"A": 20, "Exhausted": 13})]
        self.assertEqual(rcvcheck.transfer_line(official, TRANSFERS, name_key),
                         "; transfers MOVED between destinations in 1 of 2 eliminations (C)")

    def test_nothing_to_compare_says_nothing(self):
        self.assertEqual(rcvcheck.transfer_line([("D", {"A": 1})], TRANSFERS, name_key), "")


class PassReport(unittest.TestCase):

    def test_final_states_notes_and_wrapped_names(self):
        self.assertEqual(rcvcheck.pass_report(PASS_REPORT),
                         {"ANNE SMITH": [600, 650], "BOB WRITE-A-VERY-LONG-NAME-THAT-WRAPS": [300, 350],
                          "Write-In": [100, 0]})


if __name__ == "__main__":
    unittest.main()
