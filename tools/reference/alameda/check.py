"""Check the irv ruleset against Alameda County's official single-seat ranked-choice counts.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of PASS_REPORTS or CONTESTS below (all, by default): the Oakland, Berkeley and San
Leandro contests that FairVote's collection of single-winner RCV cast vote records
(https://doi.org/10.7910/DVN/AMK8PJ) has and the County still publishes the count of: its counting
system's pass reports for 2010-2017 (under https://acvote.alamedacountyca.gov/acvote-assets/pdf/elections/,
read with pdftotext), and its Dominion "RCV Detailed Reports" for 2022, 2024 and April 2025 (under
https://alamedacountyca.gov/rovresults/rcv/). The counts of 2018 and 2020 aren't published, and no
copy was archived. Each record and report is fetched into DATA_DIR, the record converted
(../cvr_to_blt.py: an overvote ends the ballot; a skipped ranking is passed over; a
candidate ranked again counts once) and counted with the `irv` ruleset.

Up to 2017 the County eliminated one candidate a pass, as this count does, but stopped once a
candidate had a majority, and counted write-ins as a candidate, Write-In, except where a write-in
had qualified (Berkeley's 2012 mayoral race: there the report has Write-In with no votes, and the
record's write-ins are passed over). Every pass must be one of this count's rounds, in order, with
the same candidates continuing and the same votes, and the same candidate must win.

From 2022, a write-in is passed over, and every official round's tallies are compared with this
count's rounds, in order, to the vote, and the elimination order and the winner must be the same.
In 2022 the County's count held back, in its first round only, some ballots whose first ranking is
skipped or a write-in ("Suspended"), which join from the second round; that first round is
compared with the record's first rankings instead. Where the record isn't quite the ballots the
County counted (a few votes a candidate, from the first round on), each round's largest difference
is reported, and the contest passes on the same eliminations and winner. Each elimination's
transfers the report lists, by where the ballots went, are compared too (rcvcheck.transfer_line):
the same; off one way, ballots the record lacks or has over the count; or MOVED, more to one
destination and fewer to another, as a rule of reading the ballots that differs would do.
"""
import collections
import csv
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from rcvcheck import (DATAVERSE, count, fetch, main, match_rounds, name_key as key, pass_report,  # noqa: E402
                      pass_exhausted_line, pdf_text, stale, summary, transfer_line, winner)

CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
REPORT = "https://alamedacountyca.gov/rovresults/rcv/{election}/{race}/RcvDetailedReport.xml"
PASS_REPORT = "https://acvote.alamedacountyca.gov/acvote-assets/pdf/elections/{path}"
NS = "{RcvDetailedReport}"
NOT_CANDIDATES = {"Blanks", "Exhausted", "Overvotes", "Remainder Points", "Suspended", "Surplus Transfer Remainder"}
# contest: (Dataverse file id, County election id, race)
CONTESTS = {
    "oakland-2022-mayor": (11593507, 248, "Oakland/001-Mayor"),
    "oakland-2022-council-4": (11593481, 248, "Oakland/003-CityCouncil_District_4"),
    "oakland-2022-council-6": (11593483, 248, "Oakland/004-CityCouncil_District_6"),
    "oakland-2022-auditor": (11593449, 248, "Oakland/005-CityAuditor"),
    "oakland-2022-school-2": (11593470, 248, "Oakland/006-SchoolDirector_District_2"),
    "oakland-2022-school-4": (11593488, 248, "Oakland/007-SchoolDirector_District_4"),
    "oakland-2022-school-6": (11593499, 248, "Oakland/008-SchoolDirector_District_6"),
    "berkeley-2024-mayor": (11593220, 252, "Berkeley/001-Mayor"),
    "berkeley-2024-council-2": (11593243, 252, "Berkeley/002-CityCouncil_District_2"),
    "berkeley-2024-council-3": (11593206, 252, "Berkeley/003-CityCouncil_District_3"),
    "berkeley-2024-council-5": (11593210, 252, "Berkeley/004-CityCouncil_District_5"),
    "berkeley-2024-council-6": (11593204, 252, "Berkeley/005-CityCouncil_District_6"),
    "oakland-2024-council-at-large": (11593478, 252, "Oakland/001-CityCouncil_AtLarge"),
    "oakland-2024-council-1": (11593492, 252, "Oakland/002-CityCouncil_District_1"),
    "oakland-2024-council-3": (11593469, 252, "Oakland/003-CityCouncil_District_3"),
    "oakland-2024-council-5": (11593473, 252, "Oakland/004-CityCouncil_District_5"),
    "oakland-2024-council-7": (11593501, 252, "Oakland/005-CityCouncil_District_7"),
    "oakland-2024-city-attorney": (11593455, 252, "Oakland/006-CityAttorney"),
    "oakland-2024-school-1": (11593462, 252, "Oakland/007-SchoolDirector_District_1"),
    "oakland-2024-school-3": (11593472, 252, "Oakland/008-SchoolDirector_District_3"),
    "oakland-2024-school-5": (11593461, 252, "Oakland/009-SchoolDirector_District_5"),
    "oakland-2024-school-7": (11593511, 252, "Oakland/010-SchoolDirector_District_7"),
    "oakland-2025-mayor": (11593491, 257, "Oakland/001-Mayor"),
    "oakland-2025-council-2": (11593446, 257, "Oakland/002-CityCouncil_District_2"),
}

# 2010-2017: the counting system's pass report, as the County still publishes it.
# contest: (Dataverse file id, the report under acvote-assets/pdf/elections/)
PASS_REPORTS = {}
for election, reports in (
        ("2010/11022010", {
            "berkeley-2010-auditor": (11593248, "berkeley/city_auditor/"
                                                "november-2-2010-pass-report-berkeley-city-auditor.pdf"),
            "berkeley-2010-council-1": (11593207, "berkeley/city_councilmember_district_1/"
                                                  "november-2-2010-pass-report-berkeley-council-member-dist-1.pdf"),
            "berkeley-2010-council-4": (11593217, "berkeley/city_councilmember_district_4/"
                                                  "november-2-2010-pass-report-berkeley-council-member-dist-4.pdf"),
            "berkeley-2010-council-7": (11593233, "berkeley/city_councilmember_district_7/"
                                                  "november-2-2010-pass-report-berkeley-council-member-dist-7.pdf"),
            "berkeley-2010-council-8": (11593247, "berkeley/city_councilmember_district_8/"
                                                  "november-2-2010-pass-report-berkeley-council-member-dist-8.pdf"),
            "oakland-2010-mayor": (11593459, "oakland/mayor/november-2-2010-pass-report-oakland-mayor.pdf"),
            "oakland-2010-auditor": (11593448, "oakland/city_auditor/"
                                               "november-2-2010-pass-report-oakland-city-auditor.pdf"),
            "oakland-2010-council-2": (11593463, "oakland/city_councilmember_district_2/"
                                                 "november-2-2010-rcv-oakland-city-council-district-2-pass-report.pdf"),
            "oakland-2010-council-4": (11593451, "oakland/city_councilmember_district_4/"
                                                 "november-2-2010-rcv-oakland-city-council-district-4-pass-report.pdf"),
            "oakland-2010-council-6": (11593453, "oakland/city_councilmember_district_6/"
                                                 "november-2-2010-rcv-oakland-city-council-district-6-pass-report.pdf"),
            "oakland-2010-school-2": (11593487, "oakland/school_director_district_2/"
                                                "november-2-2010-rcv-oakland-school-director-district-2-pass-report.pdf"),
            "oakland-2010-school-4": (11593471, "oakland/school_director_district_4/"
                                                "november-2-2010-rcv-oakland-school-director-district-4-pass-report.pdf"),
            "oakland-2010-school-6": (11593447, "oakland/school_director_district_6/"
                                                "november-2-2010-rcv-oakland-school-director-district-6-pass-report.pdf"),
            "san-leandro-2010-mayor": (11593619, "san_leandro/mayor/november-2-2010-pass-report-san-leandro-mayor.pdf"),
            "san-leandro-2010-council-1": (11593634, "san_leandro/city_councilmember_district_1/"
                                                     "nov-2-2010-pass-report-san-leandro-council-mem-dist-1.pdf"),
            "san-leandro-2010-council-3": (11593612, "san_leandro/city_councilmember_district_3/"
                                                     "nov-2-2010-pass-report-san-leandro-council-mem-dis-3.pdf"),
            "san-leandro-2010-council-5": (11593641, "san_leandro/city_councilmember_district_5/"
                                                     "nov-2-2010-pass-report-san-leandro-council-mem-dis-5.pdf"),
        }),
        ("2012/11062012", {
            "berkeley-2012-mayor": (11593219, "berkeley/mayor/nov-6-2012-pass-report-mayor.pdf"),
            "berkeley-2012-council-2": (11593236, "berkeley/city_councilmember_district_2/"
                                                  "nov-6-2012-pass-report-council-member-dist2.pdf"),
            "berkeley-2012-council-3": (11593234, "berkeley/city_councilmember_district_3/"
                                                  "nov-6-2012-pass-report-council-member-dist3.pdf"),
            "berkeley-2012-council-5": (11593230, "berkeley/city_councilmember_district_5/"
                                                  "nov-6-2012-pass-report-council-member-dist-5.pdf"),
            "berkeley-2012-council-6": (11593222, "berkeley/city_councilmember_district_6/"
                                                  "nov-6-2012-pass-report-council-member-dist-6.pdf"),
            "oakland-2012-city-attorney": (11593490, "oakland/city_attorney/"
                                                     "nov-6-2012-pass-report-oakland-city-attorney.pdf"),
            "oakland-2012-council-at-large": (11593516, "oakland/city_councilmember_district_at_large/"
                                                        "nov-6-2012-pass-report-oakland-council-member-at-large.pdf"),
            "oakland-2012-council-1": (11593494, "oakland/city_councilmember_district_1/"
                                                 "nov-6-2012-pass-report-oakland-council-member-dist-1.pdf"),
            "oakland-2012-council-3": (11593467, "oakland/city_councilmember_district_3/"
                                                 "nov-6-2012-pass-report-oakland-council-member-dist-3.pdf"),
            "oakland-2012-council-5": (11593486, "oakland/city_councilmember_district_5/"
                                                 "nov-6-2012-pass-report-oakland-council-member-dist-5.pdf"),
            "oakland-2012-council-7": (11593504, "oakland/city_councilmember_district_7/"
                                                 "nov-6-2012-pass-report-oakland-council-member-dist-7.pdf"),
            "oakland-2012-school-1": (11593496, "oakland/school_director_district_1/"
                                                "nov-6-2012-pass-report-oakland-school-director-dist-1.pdf"),
            "oakland-2012-school-3": (11593510, "oakland/school_director_district_3/"
                                                "nov-6-2012-pass-report-oakland-school-director-dist-3.pdf"),
            "oakland-2012-school-5": (11593457, "oakland/school_director_district_5/"
                                                "nov-6-2012-pass-report-oakland-school-director-dist-5.pdf"),
            "oakland-2012-school-7": (11593480, "oakland/school_director_district_7/"
                                                "nov-6-2012-pass-report-oakland-school-director-dist-7.pdf"),
            "san-leandro-2012-council-2": (11593598, "san_leandro/city_councilmember_district_2/"
                                                     "nov-6-2012-pass-report-san-leandro-council-member-dist-2.pdf"),
            "san-leandro-2012-council-4": (11593626, "san_leandro/city_councilmember_district_4/"
                                                     "nov-6-2012-pass-report-san-leandro-council-member-dist-4.pdf"),
            "san-leandro-2012-council-6": (11593645, "san_leandro/city_councilmember_district_6/"
                                                     "nov-6-2012-pass-report-san-leandro-council-member-dist-6.pdf"),
        }),
        ("2014/11042014", {
            "berkeley-2014-auditor": (11593224, "berkeley/city_auditor/"
                                                "nov-4-2014-pass-report-city-auditor-berkeley.pdf"),
            "berkeley-2014-council-1": (11593212, "berkeley/city_council_district_1/"
                                                  "nov-4-2014-pass-report-city-council-berkeley-dist1.pdf"),
            "berkeley-2014-council-7": (11593231, "berkeley/city_council_district_7/"
                                                  "nov-4-2014-pass-report-city-council-berkeley-dist7.pdf"),
            "berkeley-2014-council-8": (11593211, "berkeley/city_council_district_8/"
                                                  "nov-4-2014-pass-report-city-council-berkeley-dist8.pdf"),
            "oakland-2014-mayor": (11593482, "oakland/mayor/nov-4-2014-pass-report-mayor-oakland.pdf"),
            "oakland-2014-auditor": (11593517, "oakland/city_auditor/nov-4-2014-pass-report-city-auditor-oakland.pdf"),
            "oakland-2014-council-2": (11593458, "oakland/city_council_district_2/"
                                                 "nov-4-2014-pass-report-city-council-oakland-dist2.pdf"),
            "oakland-2014-council-4": (11593512, "oakland/city_council_district_4/"
                                                 "nov-4-2014-pass-report-city-council-oakland-dist4.pdf"),
            "oakland-2014-council-6": (11593500, "oakland/city_council_district_6/"
                                                 "nov-4-2014-pass-report-city-council-oakland-dist6.pdf"),
            "oakland-2014-school-2": (11593495, "oakland/school_director_district_2/"
                                                "nov-4-2014-pass-report-school-director-oakland-dist2.pdf"),
            "oakland-2014-school-4": (11593484, "oakland/school_director_district_4/"
                                                "nov-4-2014-pass-report-school-director-oakland-dist4.pdf"),
            "oakland-2014-school-6": (11593506, "oakland/school_director_district_6/"
                                                "nov-4-2014-pass-report-school-director-oakland-dist6.pdf"),
            "san-leandro-2014-mayor": (11593614, "san_leandro/mayor/nov-4-2014-pass-report-mayor-san-leandro.pdf"),
            "san-leandro-2014-council-1": (11593651, "san_leandro/city_council_district_1/"
                                                     "nov-4-2014-pass-report-city-council-san-leandro-d1.pdf"),
            "san-leandro-2014-council-3": (11593592, "san_leandro/city_council_district_3/"
                                                     "nov-4-2014-pass-report-city-council-san-leandro-d3.pdf"),
            "san-leandro-2014-council-5": (11593644, "san_leandro/city_council_district_5/"
                                                     "nov-4-2014-pass-report-city-council-san-leandro-d5.pdf"),
        }),
        ("2016/11082016", {
            "berkeley-2016-mayor": (11593239, "berkeley/mayor/nov-8-2016-pass-results-report-mayor.pdf"),
            "berkeley-2016-council-2": (11593202, "berkeley/city_councilmember_district_2/"
                                                  "nov-8-2016--pass-report-councilmember-dist2.pdf"),
            "berkeley-2016-council-3": (11593223, "berkeley/city_councilmember_district_3/"
                                                  "nov-8-2016-pass-report-councilmember-dist3.pdf"),
            "berkeley-2016-council-5": (11593218, "berkeley/city_councilmember_district_5/"
                                                  "nov-8-2016-pass-report-councilmember-dist5.pdf"),
            "berkeley-2016-council-6": (11593226, "berkeley/city_councilmember_district_6/"
                                                  "nov-8-2016-pass-report-councilmember-dist6.pdf"),
            "oakland-2016-city-attorney": (11593450, "oakland/city_attorney/"
                                                     "nov-8-2016-pass-report-city-attorney-oak.pdf"),
            "oakland-2016-council-at-large": (11593477, "oakland/city_councilmember_district_at_large/"
                                                        "nov-8-2016-pass-report-city-council-at-large-oak.pdf"),
            "oakland-2016-council-1": (11593454, "oakland/city_councilmember_district_1/"
                                                 "nov-8-2016-pass-report-city-council-dist1-oak.pdf"),
            "oakland-2016-council-5": (11593514, "oakland/city_councilmember_district_5/"
                                                 "nov-8-2016-pass-report-city-council-dist5-oak.pdf"),
            "oakland-2016-council-7": (11593515, "oakland/city_councilmember_district_7/"
                                                 "nov-8-2016-pass-report-city-council-dist7-oak.pdf"),
            "oakland-2016-school-1": (11593460, "oakland/school_director_district_1/"
                                                "nov-8-2016-pass-report-school-director-dist1-oak.pdf"),
            "oakland-2016-school-3": (11593468, "oakland/school_director_district_3/"
                                                "nov-8-2016-pass-report-school-director-dist3-oak.pdf"),
            "oakland-2016-school-5": (11593476, "oakland/school_director_district_5/"
                                                "nov-8-2016-pass-report-school-director-dist5-oak.pdf"),
            "oakland-2016-school-7": (11593474, "oakland/school_director_district_7/"
                                                "nov-8-2016-pass-report-school-director-dist7-oak.pdf"),
            "san-leandro-2016-council-2": (11593587, "san_leandro/city_councilmember_district_2/"
                                                     "nov-8-2016-pass-report-city-council-dist2-sl.pdf"),
            "san-leandro-2016-council-4": (11593606, "san_leandro/city_councilmember_district_4/"
                                                     "nov-8-2016-pass-report-city-council-dist4-sl.pdf"),
            "san-leandro-2016-council-6": (11593605, "san_leandro/city_councilmember_district_6/"
                                                     "nov-8-2016-pass-report-city-council-dist6-sl.pdf"),
        }),
        ("2017/03072017", {
            "berkeley-2017-council-4": (11593208, "march-7-2017-pass-report-results.pdf"),
        })):
    for contest, (file_id, path) in reports.items():
        PASS_REPORTS[contest] = (file_id, f"{election}/results/rcv/{path}")


def report(path):
    """Each round's votes per candidate (by key), the suspended ballots of round 1, the candidates
    eliminated in order, the winners, and each elimination's transfers, [(eliminated, {destination:
    ballots})] (destinations "Exhausted" and "Overvotes" among them)."""
    root = ET.parse(path).getroot()
    rounds, suspended, elected, eliminated = [], 0, [], []
    moves = []
    for source in root.iter(f"{NS}sourceChoiceId"):
        match = re.match(r"Elimination transfer for candidate (.*)\.$", source.get("Textbox44") or "")
        if match:
            moves.append((match.group(1), {c.get("choiceName"): int(c.get("ballotsTransferred3"))
                                           for c in source.iter(f"{NS}choiceId")
                                           if c.get("choiceName") != c.get("sourceChoiceName")}))
    for group in root.iter(f"{NS}roundGroup"):
        summary = group.find(f"{NS}Tablix5")
        votes = {c.get("choiceName2"): Decimal(c.get("votes")) for c in summary.iter(f"{NS}choiceGroup")}
        if not rounds:
            suspended = votes.get("Suspended", Decimal(0))
        rounds.append({key(name): value for name, value in votes.items() if name not in NOT_CANDIDATES})
        for element in group.iter():
            text = element.get("Textbox21") or ""
            match = re.match(r"(.*) is (elected|eliminated) because", text)
            if match:
                into = elected if match.group(2) == "elected" else eliminated
                if key(match.group(1)) not in into:
                    into.append(key(match.group(1)))
    return rounds, suspended, elected, eliminated, moves


def first_rankings(cvr):
    """The record's first rankings per candidate (by key)."""
    reader = csv.DictReader(open(cvr, newline="", encoding="utf-8-sig"))
    columns = sorted((c for c in reader.fieldnames if c.startswith("rank")), key=lambda c: int(c[4:]))
    return collections.Counter(key(row[columns[0]].strip()) for row in reader)


def check(data, contest, jar):
    file_id, election, race = CONTESTS[contest]
    rounds, suspended, elected, eliminated, moves = report(fetch(os.path.join(data, contest + ".xml"),
                                                          REPORT.format(election=election, race=race)))
    cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
    blt = os.path.join(data, contest + ".blt")
    if stale(blt, cvr):
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--title", contest], check=True)
    ours, error = count(jar, blt)
    if error:
        return False, f"{contest}: count failed: {error}"
    ours_rounds = [{key(name): Decimal(votes) for name, votes in r["tallies"].items()} for r in ours["rounds"]]
    ours_eliminated = [key(r["action"]["candidate"]) for r in ours["rounds"] if r["action"]["type"] == "eliminated"]
    exact, worst = 0, Decimal(0)
    for k, figures in enumerate(rounds):
        if k == 0 and suspended:
            first = first_rankings(cvr)
            gap = max(abs(first[name] - votes) for name, votes in figures.items())
        else:
            # This count's round k is the official round k + 1's state, before the k-th action.
            mine = ours_rounds[k] if k < len(ours_rounds) else {}
            gap = max((abs(mine.get(name, Decimal(0)) - votes) for name, votes in figures.items() if votes > 0),
                      default=Decimal(0))
        exact += gap == 0
        worst = max(worst, gap)
    same_order = ours_eliminated[:len(eliminated)] == eliminated
    same = [key(name) for name in ours["electedInOrder"]] == elected
    line = (f"{contest}: {exact} of {len(rounds)} official rounds match to the vote"
            + (f" (round 1 against the record's first rankings, {int(suspended)} ballots suspended)" if suspended else "")
            + (f"; the record differs by up to {int(worst)} votes a candidate" if worst else "")
            + f"; eliminations in {'the same' if same_order else 'A DIFFERENT'} order"
            + f"; elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder'])}"
            + transfer_line(moves, ours, key))
    return same_order and same, line


def check_pass_report(data, contest, jar):
    """Every pass must be one of this count's rounds, in order, with the same candidates continuing: the
    County eliminated one candidate a pass, as this count does, but stopped at a majority, and from 2012
    its first pass only drops the write-in line, with no votes, so that pass repeats the one before."""
    file_id, path = PASS_REPORTS[contest]
    pdf = fetch(os.path.join(data, contest + ".pdf"), PASS_REPORT.format(path=path))
    text = pdf_text(pdf)
    totals = {key(name): votes for name, votes in pass_report(text).items()}
    cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
    blt = os.path.join(data, contest + ".blt")
    if stale(blt, cvr):
        # Write-ins are a candidate where the report gives them votes; where it doesn't (Berkeley's 2012 mayoral
        # race, with a qualified write-in), they are passed over.
        given = totals.get(key("Write-In"), [0])[0]
        write_ins = ["--write-in", "candidate", "--write-in-name", "WRITE-IN"] if given else []
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--title", contest] + write_ins, check=True)
    ours, error = count(jar, blt)
    if error:
        return False, f"{contest}: count failed: {error}"
    exact, gaps = match_rounds(totals, ours, key)
    same = [key(name) for name in ours["electedInOrder"]] == [winner(totals)]
    return None not in gaps and same, summary(contest, exact, gaps, same, ours["electedInOrder"], rounds="passes",
                                              round_name="pass", first=0) + pass_exhausted_line(text, ours, key)


if __name__ == "__main__":
    main(__doc__.split("\n\n")[0], list(PASS_REPORTS) + list(CONTESTS),
         lambda data, contest, jar: (check_pass_report if contest in PASS_REPORTS else check)(data, contest, jar))
