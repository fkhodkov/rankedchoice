"""Count a BLT election with pyRCV2's ERS97 counter.

usage: pyrcv2_ers97.py PYRCV2_DIR ELECTION.blt [--sheet]

PYRCV2_DIR is a checkout of pyRCV2 (https://yingtongli.me/git/pyRCV2); its counting code runs
as ordinary Python. The counter is configured as pyRCV2's own model-election test configures it
(exclusive Gregory, ERS97 quota mode, bulk exclusion, deferred surpluses, 2 decimal places),
with ties broken backwards through earlier stages and then by declared order, as this project's
`ers97` ruleset breaks them.

Prints every stage as JSON (votes, states, exhausted, loss by fraction, votes required), or
with --sheet as a count sheet in the format Ers97ModelElectionFixtureTest reads.
"""
import json
import sys
from fractions import Fraction

sys.path.insert(0, sys.argv[1])
import pyRCV2.blt  # noqa: E402
import pyRCV2.numbers  # noqa: E402
import pyRCV2.ties  # noqa: E402
from pyRCV2.method.gregory import EGSTVCounter  # noqa: E402


class TiesDeclared:
    """This project's last resort: the earliest declared of the tied candidates, either way."""
    adverb = 'by declared order'

    def __init__(self, order):
        self.order = order

    def choose_lowest(self, tied):
        return min(tied, key=lambda x: self.order.index(x[0]))

    def choose_highest(self, tied):
        return min(tied, key=lambda x: self.order.index(x[0]))


def count(path):
    pyRCV2.numbers.set_numclass(pyRCV2.numbers.Fixed)
    pyRCV2.numbers.set_dps(5)
    election = pyRCV2.blt.readBLT("".join(line for line in open(path) if not line.startswith("#")))
    counter = EGSTVCounter(election, {
        'quota': 'droop_exact', 'quota_mode': 'ers97', 'bulk_exclude': True, 'defer_surpluses': True,
        'round_quota': 2, 'round_votes': 2, 'round_tvs': 2, 'round_weights': 2,
        'papers': 'transferable', 'exclusion': 'by_value',
    })
    counter.options['ties'] = [pyRCV2.ties.TiesBackwards(counter), TiesDeclared(election.candidates)]
    stages = []
    result = counter.reset()
    while True:
        stages.append({
            "comment": (result.stage_kind + " " + result.comment) if result.stage_kind else result.comment,
            "votes": {c.name: cc.votes.pp(2) for c, cc in result.candidates.items()},
            "states": {c.name: int(cc.state) for c, cc in result.candidates.items()},
            "exhausted": result.exhausted.votes.pp(2),
            "loss": result.loss_fraction.votes.pp(2),
            "required": result.vote_required_election.pp(2) if result.vote_required_election is not None else None,
        })
        if result.__class__.__name__ == "CountCompleted":
            return stages
        result = counter.step()


def sheet(stages, source):
    lines = [f"# {source}, counted by pyRCV2's ERS97 counter (tools/reference/ers97/pyrcv2_ers97.py).",
             "# Each stage: every candidate's votes, then the non-transferable votes (exhausted plus loss",
             "# by fraction).", ""]
    for number, stage in enumerate(stages, 1):
        if stage["comment"] == "Count complete":
            continue
        lines += [f"stage {number}", f"comment: {stage['comment']}"]
        lines += [f"{name}: {votes}" for name, votes in stage["votes"].items()]
        lines += [f"non-transferable: {float(Fraction(stage['exhausted']) + Fraction(stage['loss'])):.2f}", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    stages = count(sys.argv[2])
    print(sheet(stages, sys.argv[2].split("/")[-1]) if "--sheet" in sys.argv else json.dumps(stages, indent=1))
