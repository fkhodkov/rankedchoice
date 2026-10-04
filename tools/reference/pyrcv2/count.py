"""Count a BLT election with pyRCV2.

usage: count.py PYRCV2_DIR ELECTION.blt [--preset ers97|gregory|scottish] [--sheet]

PYRCV2_DIR is a checkout of pyRCV2 (https://yingtongli.me/git/pyRCV2); its counting code runs
as ordinary Python. Presets, each matched to one of this project's rulesets:

- ers97 (--ruleset ers97): exclusive Gregory as pyRCV2's own model-election test configures it.
- gregory (--quota droop --transfer fractional --rounding exact): weighted inclusive Gregory
  in exact rational arithmetic, this project's default stv.
- scottish (--ruleset scottish): pyRCV2's "Scottish STV" preset, with transfer values truncated
  to 5 places as the rules require (the preset leaves them unrounded), in rational arithmetic
  (its 5-place fixed point adds rounding differences of its own). Even so it rounds a
  surplus from ballots of different values differently from the statute, which Highland's
  official count sheet confirms this project follows, so expect differences in the 5th place.

Ties are broken backwards through earlier stages, then by declared order, as this project's
rulesets break them. Two fixes to pyRCV2 are applied here: it stores each stage's result
without copying it, so looking back for a tie only ever sees the current votes (and
CountStepResult.clone() omits an argument), so each stage is copied as it's recorded.

Prints every stage as JSON (votes, states, exhausted, loss by fraction, votes required), exact
fractions where pyRCV2 counts exactly; or with --sheet, a count sheet in the format
Ers97ModelElectionFixtureTest reads.
"""
import json
import sys
from fractions import Fraction

sys.path.insert(0, sys.argv[1])
import pyRCV2.blt  # noqa: E402
import pyRCV2.method.base_stv  # noqa: E402
import pyRCV2.numbers  # noqa: E402
import pyRCV2.ties  # noqa: E402
from pyRCV2.method.gregory import EGSTVCounter, WIGSTVCounter  # noqa: E402

_INCLUSIVE = {'quota': 'droop', 'quota_criterion': 'geq', 'quota_mode': 'static', 'bulk_elect': True,
              'bulk_exclude': False, 'defer_surpluses': False, 'round_quota': 0, 'surplus_order': 'size',
              'papers': 'both', 'exclusion': 'one_round'}
PRESETS = {  # counter, number class, decimal places (counting, printing), options
    "ers97": (EGSTVCounter, "Fixed", (5, 2), {
        'quota': 'droop_exact', 'quota_mode': 'ers97', 'bulk_exclude': True, 'defer_surpluses': True,
        'round_quota': 2, 'round_votes': 2, 'round_tvs': 2, 'round_weights': 2,
        'papers': 'transferable', 'exclusion': 'by_value'}),
    "gregory": (WIGSTVCounter, "Rational", None, _INCLUSIVE),
    "scottish": (WIGSTVCounter, "Rational", None, {**_INCLUSIVE, 'round_tvs': 5}),
}

_make_result = pyRCV2.method.base_stv.BaseSTVCounter.make_result


def _make_result_copied(self, *args):
    result = _make_result(self, *args)
    candidates = type(result.candidates)()
    for candidate, card in result.candidates.items():
        candidates[candidate] = card.clone()
    self.step_results[-1] = type(result)(result.stage_kind, result.comment, result.logs, candidates,
                                         result.exhausted.clone(), result.loss_fraction.clone(), result.total,
                                         result.quota, result.vote_required_election)
    return result


pyRCV2.method.base_stv.BaseSTVCounter.make_result = _make_result_copied


class TiesDeclared:
    """This project's last resort: the earliest declared of the tied candidates, either way."""
    adverb = 'by declared order'

    def __init__(self, order):
        self.order = order

    def choose_lowest(self, tied):
        return min(tied, key=lambda x: self.order.index(x[0]))

    def choose_highest(self, tied):
        return min(tied, key=lambda x: self.order.index(x[0]))


def count(path, preset):
    counter_class, numbers, places, options = PRESETS[preset]
    pyRCV2.numbers.set_numclass(getattr(pyRCV2.numbers, numbers))
    if places:
        pyRCV2.numbers.set_dps(places[0])

    def show(number):
        impl = getattr(number, "impl", None)
        return str(impl) if isinstance(impl, Fraction) else number.pp(places[1])

    election = pyRCV2.blt.readBLT("".join(line for line in open(path) if not line.startswith("#")))
    counter = counter_class(election, dict(options))
    counter.options['ties'] = [pyRCV2.ties.TiesBackwards(counter), TiesDeclared(election.candidates)]
    stages = []
    result = counter.reset()
    while True:
        stages.append({
            "comment": (result.stage_kind + " " + result.comment) if result.stage_kind else result.comment,
            "votes": {c.name: show(cc.votes) for c, cc in result.candidates.items()},
            "states": {c.name: int(cc.state) for c, cc in result.candidates.items()},
            "exhausted": show(result.exhausted.votes),
            "loss": show(result.loss_fraction.votes),
            "required": show(result.vote_required_election) if result.vote_required_election is not None else None,
        })
        if result.__class__.__name__ == "CountCompleted":
            return stages
        result = counter.step()


def sheet(stages, source):
    lines = [f"# {source}, counted by pyRCV2's ERS97 counter (tools/reference/pyrcv2/count.py).",
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
    preset = sys.argv[sys.argv.index("--preset") + 1] if "--preset" in sys.argv else "ers97"
    stages = count(sys.argv[2], preset)
    print(sheet(stages, sys.argv[2].split("/")[-1]) if "--sheet" in sys.argv else json.dumps(stages, indent=1))
