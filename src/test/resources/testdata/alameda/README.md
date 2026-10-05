# Alameda County single-seat ranked-choice contests, 2022–2025

Four of the Oakland and Berkeley contests `tools/reference/alameda/check.py` checks, counted with
the `irv` ruleset by `AlamedaIrvFixtureTest`: Oakland's 2022 mayoral race (9 rounds), Berkeley's
2024 mayoral race, Oakland's 2024 District 3 council race, and Oakland's April 2025 District 2
special.

- `<contest>.blt.gz`: the ballots, from FairVote's collection of single-winner RCV cast vote
  records (https://doi.org/10.7910/DVN/AMK8PJ, file `dataverseFile`), converted by
  `tools/reference/albany/cvr_to_blt.py` (an overvote ends the ballot; a skipped ranking or a
  write-in is passed over). Identical rankings are merged.
- `<contest>.official.json`: the County's Dominion "RCV Detailed Report" (the XML at `source`):
  each round's votes per candidate, the candidates eliminated in order, the winner, and the
  ballots its 2022 count held back in round 1 only (`suspended`), which join from round 2.
