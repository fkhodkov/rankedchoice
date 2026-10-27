"""Check a Scottish ward's count against its council's official "Candidate Votes Per Stage Report".

usage: stage_report.py SCOT_ELEX_DIR REPORTS_DIR [--jar PATH]
       stage_report.py --sheet REPORT.pdf BALLOTS.blt   (a report as a count sheet, for the fixtures)

REPORTS_DIR holds councils' Candidate Votes Per Stage reports (the PDF most councils' counting
system printed in 2022), each named after the ward's scot-elex file, e.g. falkirk_2022_ward4.pdf. A report
named by ward number where scot-elex names the ward instead (Highland's) is taken for the council's 2022 ward
whose every candidate it names, and reported under that ward's name.
Each ward is counted with the `scottish` ruleset; every stage of the report, every candidate's
votes and the non-transferable votes (including those "due to rounding"), all to the report's 5
decimal places, must be one of this count's rounds, in order. Needs pdftotext (poppler).
"""
import argparse
import glob
import os
import pathlib
import re
import subprocess
import sys
import tempfile
from decimal import Decimal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402
import scot_elex  # noqa: E402
import stages  # noqa: E402
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from rcvcheck import record, write_results  # noqa: E402

NUMBER = r"-?\d[\d,]*(?:\.\d+)?"


def report_stages(pdf, names):
    """[(stage, {candidate: votes}, non-transferable)] from the report, candidates as in names."""
    text = subprocess.run(["pdftotext", "-layout", pdf, "-"], capture_output=True, text=True, check=True).stdout
    by_plain = {check.plain(name): name for name in names}
    result, votes, lost, number = [], None, Decimal(0), None
    for line in text.splitlines():
        stage = re.match(r"^\s*Stage (\d+)\s*$", line)
        if stage:
            if votes is not None:
                result.append((number, votes, lost))
            number, votes, lost = int(stage.group(1)), {}, Decimal(0)
            continue
        if votes is None:
            continue
        figures = re.findall(NUMBER, re.sub(r"\b(Elected|Excluded)\b", "", line))
        if not figures:
            continue
        value = Decimal(figures[-1].replace(",", ""))
        if line.strip().startswith("Non-transferable votes"):
            lost += value
            continue
        name = next((full for plain, full in by_plain.items() if check.plain(line).startswith(plain)), None)
        if name:
            votes[name] = value
    if votes is not None:
        result.append((number, votes, lost))
    return result


def ward_file(scot_elex_dir, key, pdf):
    """The scot-elex file for the report key names, or for a report named by ward number, the council's one 2022
    ward whose every candidate the report names; or None."""
    path = next(iter(glob.glob(os.path.join(scot_elex_dir, "blt_files", "*", key + ".blt"))), None)
    if path or not re.search(r"_2022_ward\d+$", key):
        return path
    text = check.plain(subprocess.run(["pdftotext", "-layout", pdf, "-"], capture_output=True, text=True,
                                      check=True).stdout)
    council = key.split("_2022_")[0]
    found = []
    for path in glob.glob(os.path.join(scot_elex_dir, "blt_files", "*", council + "_2022_*.blt")):
        _, names, _, _ = scot_elex.convert(pathlib.Path(path).read_text(encoding="utf-8", errors="replace"))
        if all(check.plain(name) in text for name in names):
            found.append(path)
    return found[0] if len(found) == 1 else None


def compare(rounds, official):
    """The first official stage no round matches, or None."""
    start = 0
    for number, votes, lost in official:
        hit = None
        for i in range(start, len(rounds)):
            tallies = rounds[i]["tallies"]
            set_aside = Decimal(rounds[i]["exhaustedVotes"]) + Decimal(rounds[i]["roundingLoss"])
            if all(Decimal(tallies[name]) == value for name, value in votes.items() if name in tallies) \
                    and set_aside == lost:
                hit = i
                break
        if hit is None:
            return number
        start = hit
    return None


def sheet(pdf, blt):
    """The report as a count sheet (CountSheet's format): every stage's votes and non-transferable votes."""
    ballots = pathlib.Path(blt).read_text().splitlines()
    names = [line.strip().strip('"') for line in ballots if line.startswith('"')][:-1]
    lines = [f"# {os.path.basename(pdf)}: the council's Candidate Votes Per Stage Report, read by",
             "# tools/reference/scotland/stage_report.py. Each stage: every candidate's votes, then the",
             "# non-transferable votes (including those due to rounding).", ""]
    for number, votes, lost in report_stages(pdf, names):
        lines += [f"stage {number}"] + [f"{name}: {value}" for name, value in votes.items()]
        lines += [f"non-transferable: {lost}", ""]
    return "\n".join(lines)


def main():
    if sys.argv[1:2] == ["--sheet"]:
        print(sheet(sys.argv[2], sys.argv[3]))
        return
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("scot_elex")
    parser.add_argument("reports")
    parser.add_argument("--jar", default=check.DEFAULT_JAR)
    parser.add_argument("--results", help="write each report's result to this JSON file, for the results page")
    args = parser.parse_args()
    agree, differ, figures, results = 0, [], 0, []
    for pdf in sorted(glob.glob(os.path.join(args.reports, "*.pdf"))):
        key = os.path.basename(pdf)[:-4]
        path = ward_file(args.scot_elex, key, pdf)
        if path is None:
            differ.append((key, "no such ward in scot-elex"))
            continue
        key = os.path.basename(path)[:-4]
        with tempfile.TemporaryDirectory() as work:
            _, ours = stages.count(path, args.jar, work)
        if "error" in ours:
            differ.append((key, ours["error"]))
            continue
        official = report_stages(pdf, ours["names"])
        if not official or set(official[0][1]) != set(ours["names"]):
            differ.append((key, "can't read every candidate's first preferences from the report"))
            continue
        figures += sum(len(votes) + 1 for _, votes, _ in official)
        first = compare(ours["report"]["rounds"], official)
        if first is None:
            agree += 1
            results.append(record(key, True, f"{key}: every one of {len(official)} stages matches",
                                  matched=len(official), rounds=len(official), winners_same=True))
        else:
            differ.append((key, f"stage {first} of {len(official)} matches no round"))
            results.append(record(key, False, f"{key}: {differ[-1][1]}", matched=first - 1, rounds=len(official),
                                  winners_same=True))
    print(f"{agree + len(differ)} reports: {agree} match at every stage, to 5 decimal places; {len(differ)} don't "
          f"({figures} figures compared)")
    for key, why in differ:
        print(f"  {key}: {why}")
    if args.results:
        write_results(args.results, "scotland-stage-reports", sorted(results, key=lambda r: r["contest"]))


if __name__ == "__main__":
    main()
