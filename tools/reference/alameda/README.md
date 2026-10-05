# Alameda County single-seat contests

Checks the `irv` ruleset against Alameda County's official counts of the Oakland, Berkeley and San
Leandro single-seat contests that FairVote's collection of single-winner cast vote records
(https://doi.org/10.7910/DVN/AMK8PJ) also has: 93. For 2010–2017 (69) the County still publishes
its earlier counting system's pass reports; for 2022, 2024 and April 2025 (24), its Dominion "RCV
Detailed Reports". The counts of 2018 and 2020 are no longer published, and no copy was archived.

- The records are converted with `../cvr_to_blt.py`: an overvote ends the ballot, a
  skipped ranking is passed over, a candidate ranked again counts once. From 2022 a write-in is
  passed over; up to 2017 the County counted write-ins as a candidate, Write-In
  (`--write-in candidate --write-in-name WRITE-IN`), except where a write-in had qualified
  (Berkeley's 2012 mayoral race), which `check.py` reads off the report.
- Up to 2017 the County eliminated one candidate a pass but stopped at a majority, and from 2012
  its first pass only drops the empty write-in line; `check.py` requires every pass to be one of
  this count's rounds, in order, with the same candidates continuing and the same votes.
- From 2022, `check.py` compares every official round's votes with this count's, round for round,
  to the vote, with the order of eliminations and the winner. Where the record differs from the
  County's count by a few votes from the first round on, it says by how much; such a contest
  passes on the same eliminations and winner. In 2022 the County held back some ballots in round 1
  ("Suspended"); that round is compared with the record's first rankings instead.

```sh
mvn package -DskipTests
python3 tools/reference/alameda/check.py alameda-data
```

Result: see [VALIDATION.md](../../../VALIDATION.md#real-elections).
