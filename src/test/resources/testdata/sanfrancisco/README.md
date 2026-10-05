# San Francisco single-seat contests, 2019–2024

Three of the San Francisco contests `tools/reference/sanfrancisco/check.py` checks, counted with
the `irv` ruleset by `SanFranciscoIrvFixtureTest`, one from each form the Department of Elections
published its final results in: the 2019 mayoral race (an Excel report), the 2020 District 7
supervisor race (a PDF) and the 2024 District 9 supervisor race (a web page). All three match the
official count to the vote, every round.

- `<contest>.blt.gz`: the ballots, from FairVote's collection of single-winner RCV cast vote
  records (https://doi.org/10.7910/DVN/AMK8PJ, file `dataverseFile`), converted by
  `tools/reference/albany/cvr_to_blt.py` (an overvote ends the ballot; a skipped ranking or an
  unnamed write-in is passed over). Identical rankings are merged.
- `<contest>.official.json`: the official results file as the Internet Archive kept it (`source`;
  sfelections.org turns away scripted requests), read by `check.py`: each round's votes per
  candidate, the candidates eliminated in order, and the winner.
