# New York City primaries, 2021–2025

Three of the New York City primaries `tools/reference/nyc/check.py` checks, counted with the `irv`
ruleset by `NycIrvFixtureTest`: the 2021 Democratic mayoral primary (8 rounds, four candidates
defeated together in round 4), the 2023 Democratic primary for the 9th Council District (a round
that changes nothing, then two defeated together), and the 2025 Democratic mayoral primary (the
write-ins eliminated first, then nine candidates together). All three match the official count to
the vote, every round.

- `<contest>.blt.gz`: the ballots, from FairVote's collection of single-winner RCV cast vote
  records (https://doi.org/10.7910/DVN/AMK8PJ, file `dataverseFile`), converted by
  `tools/reference/cvr_to_blt.py` (an overvote ends the ballot; a skipped ranking or a
  write-in is passed over; a candidate ranked again counts once). Identical rankings are merged.
- `<contest>.official.json`: the Board of Elections' certified "Official Ranked Choice Rounds"
  page (`source`), read by `check.py`: each round's votes per candidate, and the winner. Round 1
  counts write-ins as one candidate, eliminated in round 2; the test starts from round 2, and
  `check.py` checks round 1 against the full record's first choices.
