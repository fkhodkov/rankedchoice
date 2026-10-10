"""Check the minneapolis ruleset against the City's official round-by-round summaries.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is park-YEAR or bet-YEAR for 2009, 2013, 2017, 2021 or 2025: the Park and Recreation
Board at large (3 seats) and Board of Estimate and Taxation (2 seats) contests; or one of the 29
single-seat races of 2017-2025 with a round summary (mayor-YEAR, ward-N-YEAR,
park-district-N-YEAR; SINGLE below), counted with `minneapolis` too. All, by default.
Each one's cast vote record and summary statement (linked from
https://vote.minneapolismn.gov/results-data/election-results/<year>/<park-board-at-large|bet>/)
are fetched into DATA_DIR, the record converted (../cvr_to_blt.py) and counted, and the summary
matched against it: every official round's figures must be one of this count's rounds, in
order, every candidate to the 4 decimal places of the official figures, at the official
threshold, and the same candidates elected. Where the City's last round elects without moving
votes, this count's last exclusion is transferred before the same candidates are elected; that
round isn't in the summary.

- 2017-2025: the City's records and PDF summaries (read with pdftotext), counted with the
  `minneapolis` ruleset.
- 2009: FairVote's collection of the records (https://doi.org/10.7910/DVN/STVUET), and the City's
  summaries as Excel files, read with LibreOffice (soffice); counted with the 2006 ordinance's
  rules alone (library/src/test/resources/testdata/minneapolis/minneapolis-2009.yaml), without the
  rankings test the later counts add. The summaries name candidates by surname, and some
  spellings differ from the records' ("Wahlstedt", "Wahstedt"), so names are matched loosely.
  The records lump every write-in as one, where the summaries list each; they're left out.
- 2013: FairVote's records, and the winners only (the City published no round summary for the
  Board of Estimate, and the Park Board's is a ballot-by-ballot listing), counted both ways.
"""
import csv
import difflib
import os
import re
import subprocess
import sys
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import rcvcheck  # noqa: E402
from rcvcheck import DATAVERSE, main, threshold_line, unmatched_rounds  # noqa: E402

CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
# An overvote or a skipped ranking is passed over; write-ins are one candidate, named as the City's results do.
READING = ["--overvote", "skip", "--write-in", "candidate"]
FILES = "https://vote.minneapolismn.gov/media/-www-content-assets/documents/archive/elections/"
CONFIG_2009 = os.path.join(HERE, "..", "..", "..", "library", "src", "test", "resources", "testdata", "minneapolis",
                           "minneapolis-2009.yaml")
# 2009 and 2013: (seats, FairVote's Dataverse file, the City's Excel summary or the winners).
OLDER = {
    "park-2009": (3, 11620400, "2009-MPRB-At-Large-Final-RCV.xls"),
    "bet-2009": (2, 11620399, "2009-BET-Final-RCV.xls"),
    "park-2013": (3, 11620409, ["ANNIE YOUNG", "JOHN ERWIN", "MEG FORNEY"]),
    "bet-2013": (2, 11620388, ["CAROL J. BECKER", "DAVID B WHEELER"]),
}
CONTESTS = {
    "park-2017": (3, "2017-Park-Board-At-Large-Ballot-Records.csv",
                  "2017-Park-Board-At-Large-Race-Results-Summary.pdf"),
    "park-2021": (3, "2021-Park-AL-Cast-Vote-Record.csv", "2021-Park-Board-At-Large-Round-Results-Summary.pdf"),
    "park-2025": (3, "2025-Park-At-Large-CVR.csv", "2025-Park-At-Large-RCV-Tabulation-Summary.pdf"),
    "bet-2017": (2, "2017-Board-of-Estimate-and-Taxation-Ballot-Records.csv",
                 "2017-Board-of-Estimate-and-Taxation-Results-Summary.pdf"),
    "bet-2021": (2, "2021-BET-Cast-Vote-Record.csv", "2021-BET-Round-Results-Summary.pdf"),
    "bet-2025": (2, "2025-BET-Cast-Vote-Record.csv", "2025-Board-of-Estimate-and-Taxation-RCV-Tabulation-Summary.pdf"),
}
# Single-seat races with both a cast vote record and a round summary: the mayor, City Council
# wards and Park Board districts (those won in the first round have no summary).
SINGLE = {
    "ward-1-2017": (1, "2017-Ward-1-Ballot-Records.csv", "2017-Ward-1-Results-Summary.pdf"),
    "ward-11-2017": (1, "2017-Ward-11-Ballot-Records.csv", "2017-Ward-11-Results-Summary.pdf"),
    "ward-3-2017": (1, "2017-Ward-3-Ballot-Records.csv", "2017-Ward-3-Results-Summary.pdf"),
    "ward-4-2017": (1, "2017-Ward-4-Ballot-Records.csv", "2017-Ward-4-Results-Summary.pdf"),
    "ward-5-2017": (1, "2017-Council-Ward-5-CVR.csv", "2017-Ward-5-RCV-Tabulation-Summary.pdf"),
    "ward-6-2017": (1, "2017-Ward-6-Ballot-Records.csv", "2017-Ward-6-Race-Results-Summary.pdf"),
    "ward-9-2017": (1, "2017-Ward-9-Ballot-Records-.csv", "2017-Ward-9-Race-Results-Summary.pdf"),
    "park-district-1-2017": (1, "2017-Park-Board-District-1-Ballot-Records.csv",
                             "2017-Park-Board-District-1-Results-Summary.pdf"),
    "park-district-3-2017": (1, "2017-Park-District-3-CVR.csv", "2017-Park-District-3-RCV-Tabulation-Summary.pdf"),
    "park-district-6-2017": (1, "2017-Park-District-6-CVR.csv", "2017-Park-District-6-RCV-Tabulation-Summary.pdf"),
    "ward-1-2021": (1, "2021-Ward-1-Cast-Vote-Record.csv", "2021-Council-Ward-1-Round-Results-Summary.pdf"),
    "ward-10-2021": (1, "2021-Ward-10-Cast-Vote-Record.csv", "2021-Council-Ward-10-Round-Results-Summary.pdf"),
    "ward-2-2021": (1, "2021-Ward-2-Cast-Vote-Record.csv", "2021-Council-Ward-2-Round-Results-Summary.pdf"),
    "ward-3-2021": (1, "2021-Ward-3-Cast-Vote-Record.csv", "2021-Council-Ward-3-Round-Results-Summary.pdf"),
    "ward-5-2021": (1, "2021-Ward-5-Cast-Vote-Record.csv", "2021-Council-Ward-5-Round-Results-Summary.pdf"),
    "mayor-2021": (1, "2021-Mayor-Cast-Vote-Record.csv", "2021-Mayor-Round-Results-Summary.pdf"),
    "park-district-2-2021": (1, "2021-Park-2-Cast-Vote-Record.csv",
                             "2021-Park-Board-District-2-Round-Results-Summary.pdf"),
    "park-district-3-2021": (1, "2021-Park-3-Cast-Vote-Record.csv",
                             "2021-Park-Board-District-3-Round-Results-Summary.pdf"),
    "park-district-4-2021": (1, "2021-Park-4-Cast-Vote-Record.csv",
                             "2021-Park-Board-District-4-Round-Results-Summary.pdf"),
    "park-district-5-2021": (1, "2021-Park-5-Cast-Vote-Record.csv",
                             "2021-Park-Board-District-5-Round-Results-Summary.pdf"),
    "park-district-6-2021": (1, "2021-Park-6-Cast-Vote-Record.csv",
                             "2021-Park-Board-District-6-Round-Results-Summary.pdf"),
    "ward-6-2023": (1, "2023-Ward-6-Cast-Vote-Record.csv", "2023-Ward-6-Summary-Sheet.pdf"),
    "ward-7-2023": (1, "2023-Ward-7-Cast-Vote-Record.csv", "2023-Ward-7-Summary-Sheet.pdf"),
    "ward-8-2023": (1, "2023-Ward-8-Cast-Vote-Record.csv", "2023-Ward-8-Summary-Sheet.pdf"),
    "ward-5-2025": (1, "2025-Ward-5-Cast-Vote-Record.csv", "2025-Ward-5-RCV-Tabulation-Summary.pdf"),
    "mayor-2025": (1, "2025-Mayor-Cast-Vote-Record.csv", "2025-Mayor-Round-Results-Summary.pdf"),
    "park-district-1-2025": (1, "2025-Park-District-1-CVR.csv", "2025-Park-District-1-RCV-Tabulation-Summary.pdf"),
    "park-district-4-2025": (1, "2025-Park-District-4-CVR.csv", "2025-Park-District-4-RCV-Tabulation-Summary.pdf"),
    "park-district-5-2025": (1, "2025-Park-District-5-CVR.csv", "2025-Park-District-5-RCV-Tabulation-Summary.pdf"),
}
CONTESTS.update(SINGLE)
NUMBER = re.compile(r"^-?[\d,]+(\.\d+)?$")


def fetch(data, name, url=None):
    return rcvcheck.fetch(os.path.join(data, name), url or FILES + name)


def spreadsheet_summary(xls, data):
    """The threshold, each candidate's grand total after every round, and the exhausted votes after every round,
    from a 2009 Excel summary."""
    out = os.path.splitext(xls)[0] + ".csv"
    if not os.path.exists(out):
        subprocess.run(["soffice", "--headless", "--convert-to", "csv:Text - txt - csv (StarCalc):44,34,76",
                        "--outdir", data, xls], check=True, capture_output=True)
    rows = list(csv.reader(open(out, encoding="utf-8", errors="ignore")))
    threshold = int(next(row[1] for row in rows if row and row[0].startswith("Threshold")))
    start = next(i for i, row in enumerate(rows) if row and row[0] == "Candidate")
    totals, exhausted = {}, []
    for row in rows[start + 3:]:
        if row and row[0] == "Exhausted":
            values = [Decimal(v.replace(",", ".")) if v.strip() not in ("", "-") and not v.endswith("%") else None
                      for v in row[1:]]
            exhausted = [Decimal(0)] + [values[i] for i in range(2, len(values), 2) if values[i] is not None]
        if not row or not row[0] or row[0].startswith(("TOTAL", "Exhausted", "Over", "Under")):
            continue
        values = [Decimal(v.replace(",", ".")).quantize(Decimal("0.0001"))
                  if v.strip() not in ("", "-") and not v.endswith("%") else None for v in row[1:]]
        totals[row[0]] = [values[0]] + [values[i] for i in range(2, len(values), 2) if values[i] is not None]
    return threshold, totals, exhausted


def surname_match(name, names):
    """The one of `names` sharing `name`'s surname, allowing for a misspelling."""
    found = difflib.get_close_matches(name.split()[-1].lower(), [n.split()[-1].lower() for n in names], n=1,
                                      cutoff=0.8)
    return next((n for n in names if found and n.split()[-1].lower() == found[0]), None)


def official_figure(figures, name):
    """The summary's figure for a candidate: by name, or, where the record spells it differently
    ("Phillipe", "Philippe"), by surname."""
    if name in figures:
        return figures[name]
    match = surname_match(name, list(figures))
    return figures.get(match) if match else None


def count(blt, jar, rules):
    ours, error = rcvcheck.count(jar, blt, rules)
    if error:
        raise RuntimeError(error)
    return ours


def check_older(data, contest, jar):
    seats, file_id, official = OLDER[contest]
    blt = os.path.join(data, contest + ".blt")
    cvr = fetch(data, contest + "-fairvote.csv", DATAVERSE.format(id=file_id))
    if rcvcheck.stale(blt, cvr):
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--seats", str(seats), "--title", contest] + READING
                       + ["--write-in-name", "Write-ins"], check=True)
    if isinstance(official, list):
        line = f"{contest}: winners only"
        ok = True
        for label, rules in (("the 2006 rules", ["--config", CONFIG_2009]),
                             ("minneapolis", ["--ruleset", "minneapolis"])):
            elected = sorted(count(blt, jar, rules)["electedInOrder"])
            same = elected == sorted(official)
            ok = ok and same
            line += f"; by {label}, {'the same' if same else 'DIFFERENTLY: ' + str(elected)}"
        return ok, line
    ours = count(blt, jar, ["--config", CONFIG_2009])
    threshold, totals, exhausted = spreadsheet_summary(fetch(data, official), data)
    rounds = max(len(values) for values in totals.values())
    start, unmatched, first_round = 0, [], []
    for k in range(rounds):
        def agrees(name, votes):
            if name == "Write-ins":
                return True
            match = surname_match(name, list(totals))
            return match is not None and len(totals[match]) > k and totals[match][k] == Decimal(votes)
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if all(agrees(name, votes) for name, votes in ours["rounds"][i]["tallies"].items())), None)
        if hit is None:
            unmatched.append(k + 1)
            if k == 0:
                first_round = [f"{name} {votes} (official {totals[surname_match(name, list(totals))][0]})"
                               for name, votes in ours["rounds"][0]["tallies"].items()
                               if name != "Write-ins" and not agrees(name, votes)]
        else:
            start = hit
    leaders = sorted(totals, key=lambda name: totals[name][-1], reverse=True)[:seats]
    same = sorted(surname_match(name, list(totals)) for name in ours["electedInOrder"]) == sorted(leaders)
    line = (f"{contest}: threshold {Decimal(ours['quota']):f} (official {threshold}); "
            f"{rounds - len(unmatched)} of {rounds} official rounds match a round of this count; "
            f"elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder'])}")
    # A first count that differs, where every later round matches, is the record's, not the count's.
    record_only = unmatched == [1] and rounds > 1
    if record_only:
        line += f"; the record's first count differs from the City's: {', '.join(first_round)}"
    elif unmatched:
        line += f"; unmatched official rounds {unmatched}"
    # The record lumps the write-ins, which the City lists each: they're left out of the matching, as above.
    line += rcvcheck.exhausted_line(
        [({name: values[k] for name, values in totals.items() if len(values) > k}, exhausted[k], None)
         for k in range(min(rounds, len(exhausted)))], ours,
        lambda figures, name: figures.get(surname_match(name, list(figures))), ignore=("Write-ins",))
    return (not unmatched or record_only) and same, line


def summary(pdf):
    """The threshold, each candidate's grand total after every round, and the exhausted votes after every round (none
    where the statement has no Exhausted row), from the summary statement."""
    text = rcvcheck.pdf_text(pdf)
    lines = text.splitlines()
    threshold = int(re.search(r"Threshold:\s+([\d,]+)", text).group(1).replace(",", ""))
    start = next(i for i, line in enumerate(lines) if line.strip().startswith("Candidate"))
    totals, name = {}, ""
    for line in lines[start + 1:]:
        if line.startswith("TOTAL"):
            break
        tokens = line.split()
        values = [t for t in tokens if NUMBER.match(t) or t == "-" or t.endswith("%")]
        words = tokens[:len(tokens) - len(values)]
        name += ("" if name.endswith("-") or not name else " ") + " ".join(words)
        if not values:
            continue    # a name the summary wraps onto the next line
        values = [v.replace(",", "") for v in values if not v.endswith("%")]
        if len(values) % 2 == 0:
            values = values[:-1]    # a last round that moves no votes: "-"
        first = Decimal(0) if values[0] == "-" else Decimal(values[0])
        totals[name] = [first] + [Decimal(v) for v in values[2::2]]
        name = ""
    exhausted = totals.pop("Exhausted", None)
    return threshold, totals, exhausted


def check(data, contest, jar):
    if contest in OLDER:
        return check_older(data, contest, jar)
    seats, cvr, pdf = CONTESTS[contest]
    blt = os.path.join(data, contest + ".blt")
    record = fetch(data, cvr)
    if rcvcheck.stale(blt, record):
        subprocess.run([sys.executable, CONVERTER, record, blt, "--seats", str(seats), "--title", contest]
                       + READING + ["--write-in-name", "Undeclared Write-ins"], check=True)
    ours, error = rcvcheck.count(jar, blt, ["--ruleset", "minneapolis"])
    if error:
        return False, f"{contest}: count failed: {error}"
    threshold, totals, exhausted = summary(fetch(data, pdf))
    rounds = len(next(iter(totals.values())))
    unmatched = unmatched_rounds([(k + 1, {name: values[k] for name, values in totals.items()}) for k in range(rounds)],
                                 ours, official_figure)
    # Those the summary elects: the leaders after its last round (where the last defeated still shows votes).
    elected = sorted(sorted(totals, key=lambda name: totals[name][-1], reverse=True)[:seats])
    same = sorted(surname_match(name, elected) or name for name in ours["electedInOrder"]) == elected
    line = threshold_line(contest, ours, threshold, rounds - len(unmatched), rounds, same,
                          f"{ours['electedInOrder']} vs {elected}")
    if unmatched:
        line += f"; first unmatched official round {unmatched[0]}"
    if exhausted:
        line += rcvcheck.exhausted_line([({name: values[k] for name, values in totals.items()}, exhausted[k], None)
                                         for k in range(min(rounds, len(exhausted)))], ours, official_figure)
    return not unmatched and same and Decimal(ours["quota"]) == threshold, line


if __name__ == "__main__":
    main(__doc__.split("\n\n")[0], list(OLDER) + list(CONTESTS), check)
