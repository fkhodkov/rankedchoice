# Alaska

Checks the `irv` ruleset against the Alaska Division of Elections' ranked-choice counts: the 22
contests of the August 2022 special election and the November 2022 and 2024 general elections
that went past a first round (US House each time, US Senate in 2022, and 18 State House and
Senate contests).

- `cvr_to_blt.py` converts a cast vote record from FairVote's collection of single-winner RCV
  records (https://doi.org/10.7910/DVN/AMK8PJ) to BLT, reading the ballots as the Division's
  Dominion reports say: an unresolved write-in is passed over, two skipped rankings in a row end
  the ballot, and so does an overvote.
- `check.py` fetches each record and the Division's "RCV Detailed Report" as the Internet Archive
  kept it (elections.alaska.gov turns away scripted requests), converts and counts the record
  with `irv`, and compares every official round's votes with this count's, round for round, with
  the elimination order and the winner. Where the record differs from the Division's count by a
  few votes, each round's largest difference is reported; such a contest passes on the same
  eliminations and winner.

```sh
mvn package -DskipTests
python3 tools/reference/alaska/check.py alaska-data
```

The statewide records are about 60 MB each. Result: see
[VALIDATION.md](../../../VALIDATION.md#real-elections).
