# Albany, California

Checks the `albany` ruleset against Alameda County's official round-by-round results for
Albany's multi-seat City Council and Board of Education contests of 2022 and 2024.

- The records, from FairVote's collection of proportional RCV records
  (https://doi.org/10.7910/DVN/STVUET; a row per ballot, columns `rank1`...), are converted with
  `../cvr_to_blt.py`, its defaults reading the ballots as the County's Dominion count does: an
  overvote ends the ballot, a skipped ranking or an unqualified write-in is passed over.
- `check.py` fetches each contest's record from Dataverse and the County's "RCV Detailed Report"
  XML, converts and counts it (2024 with `albany`, 2022 with the County's settings then,
  `src/test/resources/testdata/albany/albany-2022.yaml`), and matches every official round to a
  round of this count, every candidate to the report's decimal places, with the first threshold
  and the candidates elected.

```sh
mvn package -DskipTests
python3 tools/reference/albany/check.py albany-data
```

Result: see [VALIDATION.md](../../../VALIDATION.md#real-elections).
