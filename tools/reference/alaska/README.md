# Alaska

Checks the `irv` ruleset against the Alaska Division of Elections' ranked-choice counts: the 22
contests of the August 2022 special election and the November 2022 and 2024 general elections
that went past a first round (US House each time, US Senate in 2022, and 18 State House and
Senate contests).

- The records, from FairVote's collection of single-winner RCV records
  (https://doi.org/10.7910/DVN/AMK8PJ), are converted with `../cvr_to_blt.py --skipped two-end
  --repeat skip`, reading the ballots as the Division's Dominion reports say: an unresolved
  write-in is passed over, two skipped rankings in a row end the ballot, and so does an overvote.
  A candidate ranked again, or a write-in after a write-in, counts as a skipped ranking: the
  reports' transfers, by where each eliminated candidate's ballots went, show it to the ballot.
- `check.py` fetches each record and the Division's "RCV Detailed Report" as the Internet Archive
  kept it (elections.alaska.gov turns away scripted requests), converts and counts the record
  with `irv`, and compares every official round's votes with this count's, round for round, with
  the elimination order and the winner. Where the record differs from the Division's count by a
  few votes, each round's largest difference is reported; such a contest passes on the same
  eliminations and winner. Each elimination's transfers, by where the ballots went, are compared
  with the report's too: the same, off one way (ballots the record lacks or has over), or moved
  between destinations, which points to a rule of reading the ballots that differs.
- FairVote's 2022 records are the Division's own: its Dominion cast vote record export of the
  2022 general election (`results/22GENR/rcv/CVR_Export.zip`, as the Internet Archive kept it on
  23 December 2022) and of the August special, read anew (each session's adjudicated version; at
  each ranking the marks that are unambiguous or adjudicated votes), give every one of the 13
  contests exactly the figures FairVote's records do. The 2024 export the Archive kept, dated 30 November 2024, isn't the set
  counted: its US House first round is 4,035 votes over the Division's, every candidate higher.

```sh
mvn package -DskipTests
python3 tools/reference/alaska/check.py alaska-data
```

The statewide records are about 60 MB each. Result: see
[VALIDATION.md](../../../VALIDATION.md#real-elections).
