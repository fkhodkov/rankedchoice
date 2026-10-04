# Minneapolis Park Board at large and Board of Estimate and Taxation, 2017–2025

The City's multi-seat ranked-choice contests of 2017, 2021 and 2025: the Park and Recreation Board
at large (3 seats) and the Board of Estimate and Taxation (2 seats), checked by
`MinneapolisFixtureTest`.

- `<contest>.blt`: the ballots, from the City's cast vote record for the contest (linked from
  https://vote.minneapolismn.gov/results-data/election-results/<year>/<park-board-at-large|bet>/),
  converted by `tools/reference/minneapolis/cvr_to_blt.py`. Identical rankings are merged.
- `<contest>.official.json`: the City's summary statement for the contest (the PDF named in
  `source`), read by `tools/reference/minneapolis/check.py`: the threshold, every candidate's
  grand total after each round, and the candidates it elects. The 2025 statements are marked
  "unofficial".

The `minneapolis` ruleset's rules beyond the 2006 ordinance are inferred from these counts: the
2017 and 2025 Park Board counts defeat candidates ranked on too few ballots to win, and the 2021
and 2025 ones show a candidate over the threshold receiving ballots until their surplus moves.
