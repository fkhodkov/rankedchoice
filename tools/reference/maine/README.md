# Maine

Checks the `irv` ruleset against the Maine Secretary of State's "RCV Summary Reports" for the
contests it counted by ranked choice and whose ballots FairVote's collection of single-winner RCV
cast vote records (https://doi.org/10.7910/DVN/AMK8PJ) has: 11, from the 2018 Democratic primary
for governor and the 2018, 2022 and 2024 races for Congress in the 2nd District to five
legislative primaries of 2020. And the five primaries of June 2026, whose ballots the State
publishes itself, each in several Excel exports that `check.py` fetches and joins.

- The records are converted with `../cvr_to_blt.py --skipped two-end --write-in candidate`: two skipped
  rankings in a row end the ballot (those before the first choice included), an overvote ends
  it, a candidate ranked again counts once, and write-ins are one candidate. That reproduces the
  reports' first rounds to the vote, the ballots exhausted by overvotes and undervotes included.
- The State defeats together the lowest candidates who couldn't overtake the next one up, and
  stops at a majority; `check.py` requires every official round to be one of this count's
  rounds, in order, with the same candidates continuing, and the same winner, and reports each
  round's largest difference in votes.
- The reports are Excel files (2018, 2020; LibreOffice's `soffice`) or PDFs (2022; `pdftotext`).
  The 2024 report is a scanned page with no text, so its figures are written into `check.py`;
  its one round starts after the write-ins are eliminated.

```sh
mvn package -DskipTests
python3 tools/reference/maine/check.py maine-data
```

Result: see [VALIDATION.md](../../../VALIDATION.md#real-elections).
