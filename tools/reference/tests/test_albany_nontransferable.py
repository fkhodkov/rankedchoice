"""The Albany checker's comparison of the votes no longer with a candidate: the report's exhausted votes and overvotes
past the first round's against this count's exhausted votes, its rounding remainders against this count's loss.

python3 -m unittest discover -s tools/reference/tests
"""
import importlib.util
import os
import unittest
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("albany_check", os.path.join(HERE, "..", "albany", "check.py"))
albany = importlib.util.module_from_spec(spec)
spec.loader.exec_module(albany)

ROUNDS = [(Decimal(10), {"ANN": Decimal(12), "BOB": Decimal(8), "CY": Decimal(3)}),
          (Decimal(10), {"ANN": Decimal(10), "BOB": Decimal("9.5"), "CY": Decimal("3.25")})]


def ours(*rounds):
    return {"rounds": [{"tallies": tallies, "exhaustedVotes": gone, "roundingLoss": loss}
                       for tallies, gone, loss in rounds]}


COUNT = ours(({"Ann": "12", "Bob": "8", "Cy": "3"}, "0", "0"),
             ({"Ann": "10", "Bob": "9.5", "Cy": "3.25"}, "0.5", "0.25"))


class Nontransferable(unittest.TestCase):

    def test_the_first_rounds_overvotes_aside_the_same(self):
        gone = [(Decimal(4), Decimal(0)), (Decimal("4.5"), Decimal("0.25"))]
        self.assertEqual(albany.nontransferable_line(ROUNDS, gone, COUNT),
                         "; exhausted votes and remainders the official ones (2 rounds)")

    def test_a_round_that_differs_named(self):
        gone = [(Decimal(4), Decimal(0)), (Decimal("4.75"), Decimal(0))]
        self.assertEqual(albany.nontransferable_line(ROUNDS, gone, COUNT),
                         "; exhausted votes or remainders DIFFER in official rounds [2]")

    def test_rounds_not_in_this_count_are_left_out(self):
        rounds = [ROUNDS[0], (Decimal(10), {"ANN": Decimal(11), "BOB": Decimal(9), "CY": Decimal(3)})]
        gone = [(Decimal(4), Decimal(0)), (Decimal(4), Decimal(0))]
        self.assertEqual(albany.nontransferable_line(rounds, gone, COUNT),
                         "; exhausted votes and remainders the official ones (1 rounds)")


if __name__ == "__main__":
    unittest.main()
