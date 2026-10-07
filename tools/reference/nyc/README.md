# New York City primaries

Checks the `irv` ruleset against the Board of Elections in the City of New York's certified
round-by-round results ("Official Ranked Choice Rounds") for 124 primaries of 2021, 2023 and 2025,
from the mayoral primaries to city council, counting the Board's own cast vote records. Left out:
the 2025 District 44 and 51 specials, whose results are PDFs in another layout (one link broken,
the other decided in round 1).

- Each year's record is a zip of Excel workbooks, one per borough and way of voting, with a
  column per contest and ranking ("DEM Mayor Choice 2 of 5 Citywide (224306)": the ranking, then
  the contest's code, which names its results page) and the candidates by id, named in a
  CandidacyID_To_Name workbook. `check.py` fetches it into `DATA_DIR/<year>/`, converts the
  workbooks with LibreOffice's `soffice`, and writes every contest's ballots that rank anyone to
  `DATA_DIR/<contest>.csv`, in one pass a year.
- Those are converted with `../cvr_to_blt.py`: an overvote ends the ballot, a skipped ranking
  ("undervote") or a write-in is passed over, a candidate ranked again counts once.
- The Board counts write-ins as one candidate in round 1 and eliminates them first, in round 2,
  whatever their number (in 2025's mayoral primary, before two candidates with fewer votes). That
  is the same as passing them over, so this count's first round is the Board's second; the
  Board's first is compared with the record's first choices, write-ins included.
- The Board then eliminates the candidate with the fewest votes round by round until two are
  left, but defeats together the lowest candidates who couldn't overtake the next one up, and
  prints a round that changes nothing where a candidate first passes 50%. `check.py` requires
  every later official round to be one of this count's rounds, in order, with the same candidates
  continuing, and the same winner, and reports each round's largest difference in votes.
- Names are compared by their letters, initials left out ("Selvena N. Brooks-Powers" in the
  record, "Selvena Brooks-Powers" on the page).

```sh
mvn package -DskipTests
python3 tools/reference/nyc/check.py nyc-data
```

The zips are 109 MB (2021), 12 MB (2023) and 90 MB (2025); converting the workbooks takes most
of the first run, about ten minutes, and leaves about 4 GB in the data directory.

Until October 2026 the ballots came from FairVote's collection of single-winner RCV records
(https://doi.org/10.7910/DVN/AMK8PJ), which has 123 of these primaries. Two of its copies aren't
quite the Board's: 2023's District 13 Republican council primary lacks one ballot, and 2025's
District 35 Democratic council primary is short by up to 33 votes a candidate; and its 2025
Republican comptroller primary holds 5,402 ballots of about 30,400. From the Board's records all
three match every round.
Result: see [VALIDATION.md](../../../VALIDATION.md#real-elections).
