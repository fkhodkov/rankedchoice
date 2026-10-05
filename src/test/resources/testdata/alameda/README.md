# Alameda County single-seat ranked-choice contests, 2010–2025

Six of the Oakland and Berkeley contests `tools/reference/alameda/check.py` checks, counted with
the `irv` ruleset by `AlamedaIrvFixtureTest`: Oakland's 2010 mayoral race (10 passes) and 2014
mayoral race (16 passes), against the County's pass reports; and Oakland's 2022 mayoral race (9
rounds), Berkeley's 2024 mayoral race, Oakland's 2024 District 3 council race, and Oakland's April
2025 District 2 special, against its Dominion reports.

- `<contest>.blt.gz`: the ballots, from FairVote's collection of single-winner RCV cast vote
  records (https://doi.org/10.7910/DVN/AMK8PJ, file `dataverseFile`), converted by
  `tools/reference/cvr_to_blt.py` (an overvote ends the ballot; a skipped ranking is passed
  over; a write-in is passed over from 2022, and before that is a vote for Write-In). Identical
  rankings are merged.
- `<contest>.official.json`, 2010 and 2014: the County's pass report (the PDF at `source`): each
  pass's votes per candidate (`passes`, from pass 0), and the winner.
- `<contest>.official.json`, 2022–2025: the County's Dominion "RCV Detailed Report" (the XML at
  `source`): each round's votes per candidate, the candidates eliminated in order, the winner, and
  the ballots its 2022 count held back in round 1 only (`suspended`), which join from round 2.
