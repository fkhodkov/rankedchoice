# Scottish council elections

Checks the `scottish` ruleset against the declared result of every Scottish council election
with published ballots: 1,100 counts from MGGG's
[scot-elex](https://github.com/mggg/scot-elex) (David McCune's ballot files: Glasgow in 2007,
every council in 2012, 2017 and 2022, and 30 by-elections).

- `scot_elex.py` converts a scot-elex ballot file into a BLT this project reads (its candidate
  lines hold names and parties in several layouts).
- `official.py` collects the declared winners: for 2022, the Electoral Management Board for
  Scotland's results file (every ward's returning officer's return); for 2007–2022, every
  council's election article on Wikipedia (STV result boxes, winners in bold).
- `check.py` counts every ward with the `scottish` ruleset, matches it to its official result
  and compares the winners.
- `stages.py` compares every stage with Wikipedia's result boxes, which `official.py` reads
  count by count: every count must be one of this count's rounds, to the precision the box shows.
- `fetch_reports.py` saves a council's 2022 Candidate Votes Per Stage Reports from its election
  pages, and `stage_report.py` checks each against this count to their 5 decimal places (and
  with `--sheet` writes one as a fixture's count sheet). Falkirk, Scottish Borders and Highland
  publish theirs; Highland's 2022 wards are named, not numbered, in scot-elex, so their reports
  are renamed to match.

```sh
git clone https://github.com/mggg/scot-elex
curl -sLo emb2022.xlsx https://www.emb.scot/downloads/file/980/local-government-elections-2022-results-updated-with-postals-station-split
python3 tools/reference/scotland/official.py emb2022.xlsx official.json wp   # needs openpyxl
mvn package -DskipTests
python3 tools/reference/scotland/check.py scot-elex official.json
python3 tools/reference/scotland/stages.py scot-elex official.json
python3 tools/reference/scotland/fetch_reports.py reports sc_borders \
    https://www.scotborders.gov.uk/downloads/download/1471/2022-local-government-election-information
python3 tools/reference/scotland/fetch_reports.py reports falkirk \
    https://www.falkirk.gov.uk/elections-and-voting/local-government-election-results/ward-1
python3 tools/reference/scotland/fetch_reports.py reports highland \
    https://www.highland.gov.uk/info/800/election_results/998/2022_local_government_elections_results
python3 tools/reference/scotland/stage_report.py scot-elex reports      # needs pdftotext
```

Result: every one of the 1,070 regular ward elections elects the declared councillors. 1,068
match automatically. Two are matched by hand: Midlothian 2012 ward 1 (Penicuik), whose ballot
file truncates surnames ("Gary BEL"); and East Lothian 2017 ward 3 (Tranent/Wallyford/
Macmerry), where Wikipedia's result box is wrong (3 seats, Gordon Mackett missing) and Denis
Mollison's recount confirms the ballot file's 4 seats and the four we elect. Three
by-elections are matched too; the other 27 have no official result here.

Stages: all 40 official stage reports collected (Falkirk, Scottish Borders and Highland, 2022)
match this count at every stage to 5 decimal places. Against Wikipedia's transcriptions, 874 of
1,043 wards match at every count; the rest differ by what look like transcription slips, and
every one checked against an official source (the EMB's first preferences, Falkirk's reports)
was Wikipedia's. See [VALIDATION.md](../../../VALIDATION.md#real-elections).
