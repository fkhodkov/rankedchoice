"""Count a BLT election with OpenTally, under a preset matched to one of this project's rulesets.

usage: count.py OPENTALLY ELECTION.blt --preset NAME [--sheet]

OPENTALLY is the `opentally` binary built from https://yingtongli.me/git/OpenTally (cargo build
--release). Prints every stage of its text report as JSON in the form
tools/reference/pyrcv2/compare.py reads: each candidate's votes, the elected, the exhausted votes
and the loss by fraction. (Its CSV report can't show Meek's stages.) With --sheet, prints the
stages as a count sheet instead, the format the test fixtures read (CountSheet): every
candidate's votes and the non-transferable votes (exhausted plus loss by fraction), exactly.

Ties are broken backwards, then by OpenTally's seeded random draw, where this project falls
back to declared order: a count that reaches such a tie can differ for that reason alone.
"""
import json
import re
import subprocess
import sys
from fractions import Fraction

# OpenTally's own presets (html/presets.js), as command-line options.
PRESETS = {
    # This project's default stv: weighted inclusive Gregory, exact.
    "gregory": ["-n", "rational", "-q", "droop", "-c", "geq", "--round-quota", "0", "-s", "wig",
                "--surplus-order", "by_size", "--exclusion", "single_stage", "--no-early-bulk-elect"],
    # OpenTally's "Scottish STV" preset.
    "scottish": ["-n", "fixed", "--decimals", "5", "-q", "droop", "-c", "geq", "--round-quota", "0",
                 "--round-surplus-fractions", "5", "--round-subtransfers", "per_ballot", "-s", "wig",
                 "--surplus-order", "by_size", "--exclusion", "single_stage", "--no-early-bulk-elect"],
    # OpenTally's "ERS97" preset.
    "ers97": ["-n", "fixed", "--decimals", "5", "-q", "droop_exact", "-c", "geq", "--quota-mode", "ers97",
              "--round-quota", "2", "--round-votes", "2", "--round-surplus-fractions", "2", "--round-values", "2",
              "-s", "eg", "--transferable-only", "--surplus-order", "by_size", "--exclusion", "by_value",
              "--bulk-exclude", "--defer-surpluses", "--no-early-bulk-elect"],
    # OpenTally's "Australian Capital Territory STV" preset.
    "act": ["-n", "rational", "-q", "droop", "-c", "geq", "--round-quota", "0", "--round-votes", "6",
            "-s", "eg", "--transferable-only", "--surplus-order", "by_order", "--exclusion", "by_value",
            "--no-early-bulk-elect"],
    # OpenTally's "Australian Senate STV" preset, without its early election (which, beyond the
    # Act's s273(17) and (18), elects candidates who can no longer be overtaken): compare it with
    # the senate ruleset less electAsTheyStand, which the AEC's own counts check.
    "senate": ["-n", "rational", "-q", "droop", "-c", "geq", "--round-quota", "0", "--round-votes", "0",
               "-s", "uig", "--surplus-order", "by_order", "--exclusion", "by_value", "--no-early-bulk-elect"],
    # OpenTally's "Victorian Legislative Council STV" and "Western Australia STV" presets, without
    # early election, to compare with vic-lc and wa-lc less electAsTheyStand.
    "viclc": ["-n", "rational", "-q", "droop", "-c", "geq", "--round-quota", "0", "--round-votes", "0",
              "-s", "uig", "--surplus-order", "by_order", "--exclusion", "first_prefs_then_by_value",
              "--no-early-bulk-elect"],
    "wa": ["-n", "rational", "-q", "droop", "-c", "geq", "--round-quota", "0", "--round-votes", "0",
           "--round-subtransfers", "by_parcel", "-s", "wig", "--surplus-assume-total", "--surplus-order", "by_order",
           "--exclusion", "parcels_by_order", "--no-early-bulk-elect"],
    # Tasmania's House of Assembly, Electoral Act 2004 Schedule 4 (OpenTally has no preset): the
    # last parcel's surplus over all its papers, transfer value truncated to 6 places (as the
    # Tasmanian Electoral Commission describes), each candidate's gain in whole votes; earlier
    # surpluses first; exclusions by transfer value.
    "tasmania": ["-n", "rational", "-q", "droop", "-c", "geq", "--round-quota", "0", "--round-votes", "0",
                 "--round-surplus-fractions", "6", "-s", "eg", "--surplus-order", "by_order",
                 "--exclusion", "by_value", "--no-early-bulk-elect"],
    # OpenTally's "NSW Local Government STV" preset (Local Government (General) Regulation 2021,
    # Schedule 5), as the NSW Electoral Commission counts: weighted inclusive Gregory over the
    # transferable papers, each candidate's gain from each parcel in whole votes.
    "nswlg": ["-n", "rational", "-q", "droop", "-c", "geq", "--round-quota", "0", "--round-votes", "0",
              "--round-subtransfers", "by_parcel", "-s", "wig", "--transferable-only", "--surplus-assume-total",
              "--surplus-order", "by_order", "--exclusion", "single_stage"],
    # OpenTally's "Meek STV (2006)" and "Meek STV (New Zealand)" presets.
    "meek06": ["-n", "fixed", "--decimals", "12", "-q", "droop", "-c", "geq", "--quota-mode", "dynamic_by_total",
               "--round-quota", "9", "--round-votes", "9", "--round-surplus-fractions", "9", "--round-values", "9",
               "--meek-surplus-tolerance", "0.0001", "-s", "meek", "--defer-surpluses", "--no-early-bulk-elect"],
}
PRESETS["meeknz"] = PRESETS["meek06"] + ["--meek-nz-exclusion"]


def run(opentally, blt, preset, output):
    return subprocess.run([opentally, "stv", blt, "-o", output, "--pp-decimals", "12",
                           "-t", "backwards", "random", "--random-seed", "1"] + PRESETS[preset],
                          capture_output=True, text=True, check=True).stdout


def random_tie(opentally, blt, preset):
    """Whether OpenTally broke a tie at random, where this project would use declared order."""
    return "broken at random" in run(opentally, blt, preset, "text")


def count(opentally, blt, preset):
    """Every stage of OpenTally's text report: each candidate's votes, exhausted, loss by fraction."""
    stages = []
    for line in run(opentally, blt, preset, "text").splitlines():
        if line.startswith("Count complete"):
            break
        if re.match(r"^\d+\. ", line):
            stages.append({"comment": line, "votes": {}, "states": {}, "exhausted": "0", "loss": "0"})
        elif not stages:
            continue
        elif match := re.match(r"^- (.+?): (-?[\d.]+) \(-?[\d.]+\)(?: - (ELECTED|Excluded|Withdrawn)\b)?", line):
            name, votes, state = match.groups()
            if state != "Excluded" or Fraction(votes) != 0:
                stages[-1]["votes"][name] = votes
            stages[-1]["states"][name] = 40 if state == "ELECTED" else 0
        elif match := re.match(r"^(Exhausted|Loss by fraction): (-?[\d.]+)", line):
            stages[-1]["exhausted" if match.group(1) == "Exhausted" else "loss"] = match.group(2)
    return stages


def exact(value):
    return str(value.numerator) if value.denominator == 1 else f"{value.numerator}/{value.denominator}"


def sheet(stages, source, preset):
    lines = [f"# {source}, counted by OpenTally with the {preset} preset (tools/reference/opentally/count.py).",
             "# Each stage: every candidate's votes, then the non-transferable votes (exhausted plus loss",
             "# by fraction).", ""]
    for number, stage in enumerate(stages, 1):
        lines += [f"stage {number}", f"comment: {stage['comment']}"]
        lines += [f"{name}: {exact(Fraction(votes))}" for name, votes in stage["votes"].items()]
        lines += [f"non-transferable: {exact(Fraction(stage['exhausted']) + Fraction(stage['loss']))}", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    preset = sys.argv[sys.argv.index("--preset") + 1]
    stages = count(sys.argv[1], sys.argv[2], preset)
    print(sheet(stages, sys.argv[2].split("/")[-1], preset) if "--sheet" in sys.argv else json.dumps(stages, indent=1))
