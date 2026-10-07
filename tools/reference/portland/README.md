# Portland, Oregon City Council

Checks the `portland` ruleset against Multnomah County's official round-by-round results for the
four multi-seat City Council contests of the November 2024 general election.

- `cvr_to_blt.py` converts a contest's cast vote record (a row per ballot, a column per candidate
  per ranking) to BLT, reading the ballots as the County does, which reproduces its first round to
  the vote: an overvoted ranking is skipped (the numbered write-in lines count as marks for that
  but alone are skipped), as are empty rankings and a candidate ranked again.
- `check.py` converts and counts each district with `portland`, fetches the County's final RCTab
  tabulation summary, and matches every official round to a round of this count, every candidate
  to 4 decimal places, with the threshold and the councillors elected. A round that follows a tie
  the County drew by lot, decided otherwise than by declared order, is reported apart. In each
  matched round it also compares the votes exhausted so far (the summary's "exhausted"
  transfers) with this count's exhausted votes.

The cast vote records are linked from
https://multco.us/info/turnout-and-statistics-november-2024-general-election (Google Drive);
save them as `d1-cvr.csv` ... `d4-cvr.csv`.

```sh
mvn package -DskipTests
python3 tools/reference/portland/check.py portland-data
```

Result: see [VALIDATION.md](../../../VALIDATION.md#real-elections).
