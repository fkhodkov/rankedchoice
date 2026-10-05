# Eastpointe, Michigan

Checks Eastpointe's City Council elections of 2019 and 2021 (2 seats each, counted by Macomb
County with RCTab) against the County's round-by-round summaries, counted with the `portland`
ruleset (another RCTab count at 4 decimal places).

- `cvr_to_blt.py` converts a cast vote record from FairVote's collection of proportional RCV
  records (https://doi.org/10.7910/DVN/STVUET) to BLT, reading the ballots as the County's RCTab
  count was set to: a skipped ranking or a write-in is passed over, but two in a row end the
  ballot; an overvote ends it, and so does a candidate ranked again.
- `check.py` fetches each record and the RCTab summary published on RCVis, converts and counts
  the record, and matches every official round to a round of this count, every candidate to 4
  decimal places, with the threshold and the councillors elected. It reports the two rounds it
  lets pass: the last, after the last surplus moves, and the 2021 summary's round 4 slip.

```sh
mvn package -DskipTests
python3 tools/reference/eastpointe/check.py eastpointe-data
```

Result: see [VALIDATION.md](../../../VALIDATION.md#real-elections).
