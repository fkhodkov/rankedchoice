# Alameda County single-seat contests

Checks the `irv` ruleset against Alameda County's official round-by-round reports (Dominion "RCV
Detailed Reports") for the Oakland and Berkeley single-seat contests of 2022, 2024 and April 2025
that FairVote's collection of single-winner cast vote records
(https://doi.org/10.7910/DVN/AMK8PJ) also has: 24.

- The records are converted with `../albany/cvr_to_blt.py`, which reads them as the County's
  Dominion counts do: an overvote ends the ballot, a skipped ranking or a write-in is passed
  over.
- `check.py` fetches each record and report, converts and counts the record with `irv`, and
  compares every official round's votes with this count's, round for round, to the vote, with
  the order of eliminations and the winner. Where the record differs from the County's count by
  a few votes from the first round on, it says by how much; such a contest passes on the same
  eliminations and winner. In 2022 the County held back some ballots in round 1 ("Suspended");
  that round is compared with the record's first rankings instead.

```sh
mvn package -DskipTests
python3 tools/reference/alameda/check.py alameda-data
```

Result: see [VALIDATION.md](../../../VALIDATION.md#real-elections).
