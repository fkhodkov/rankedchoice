# San Francisco single-seat contests, 2014–2024

Six of the San Francisco contests `tools/reference/sanfrancisco/check.py` checks, counted with the
`irv` ruleset by `SanFranciscoIrvFixtureTest`, one from each form the Department of Elections
published its final results in, and one more from before 2019:

- the 2014 District 10 supervisor race (a pass report, as a PDF; two candidates defeated in one
  pass);
- the 2015 mayoral race (a web page; nine defeated in one round, five named write-ins among
  them);
- the 2018 District 10 supervisor race (a web page; a round repeated unchanged, a named
  write-in);
- the 2019 mayoral race (an Excel report);
- the 2020 District 7 supervisor race (a PDF);
- the 2024 District 9 supervisor race (a web page).

All six match the official count to the vote, every round.

- `<contest>.blt.gz`: the ballots, from FairVote's collection of single-winner RCV cast vote
  records (https://doi.org/10.7910/DVN/AMK8PJ, file `dataverseFile`), converted by
  `tools/reference/albany/cvr_to_blt.py` (an overvote ends the ballot; a skipped ranking is passed
  over, as is an unnamed write-in from 2015 on, while before that it is a vote for WRITE-IN).
  Identical rankings are merged.
- `<contest>.official.json`: the official results file as the Internet Archive kept it (`source`;
  sfelections.org turns away scripted requests), read by `check.py`: each round's votes per
  candidate, and the winner.
