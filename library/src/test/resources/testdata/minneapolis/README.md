# Minneapolis Park Board at large and Board of Estimate and Taxation, 2017–2025

The City's multi-seat ranked-choice contests of 2017, 2021 and 2025: the Park and Recreation Board
at large (3 seats) and the Board of Estimate and Taxation (2 seats), checked by
`MinneapolisFixtureTest`.

- `<contest>.blt`: the ballots, from the City's cast vote record for the contest (linked from
  https://vote.minneapolismn.gov/results-data/election-results/<year>/<park-board-at-large|bet>/),
  converted by `tools/reference/cvr_to_blt.py --overvote skip --write-in candidate` (write-ins
  named "Undeclared Write-ins"). Identical rankings are merged.
- `<contest>.official.json`: the City's summary statement for the contest (the PDF named in
  `source`), read by `tools/reference/minneapolis/check.py`: the threshold, every candidate's
  grand total after each round, and the candidates it elects. The 2025 statements are marked
  "unofficial".

The `minneapolis` ruleset's rules beyond the 2006 ordinance are inferred from these counts: the
2017 and 2025 Park Board counts defeat candidates ranked on too few ballots to win, and the 2021
and 2025 ones show a candidate over the threshold receiving ballots until their surplus moves.

`park-2009.blt` is the 2009 Park Board at large contest, from FairVote's copy of the City's record
(https://doi.org/10.7910/DVN/STVUET, converted by the same script; its write-ins are one
candidate, "Write-ins", where the City lists each, so the test leaves them out), and
`park-2009.official.json` the City's 2009 summary statement (an Excel file, read by
`tools/reference/minneapolis/check.py`), under the record's spelling of each name. It's counted
with `minneapolis-2009.yaml`: the 2006 ordinance's rules alone, without the rankings test the
later counts add.

`mayor-2021` and `ward-6-2023` are single-seat races, from the City's records and summary
statements like the others, counted with `minneapolis` (its quota is then a majority of the first
round's votes). The 2021 mayoral race needs the rankings test: by the 2006 rules alone, its
second round differs. Ward 6 defeats three candidates together, Tiger Worku's 1,127 votes among
them, and the City's count ends with two left, Jamal Osman ahead.
