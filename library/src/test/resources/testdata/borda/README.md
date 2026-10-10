# Borda: Nauru's 2019 parliamentary election

Checked by `BordaNauru2019AnabarFixtureTest` and `BordaNauru2019BoeFixtureTest`, under Dowdall
scoring (`--borda-scoring dowdall`, Nauru's system).

- `nauru-2019-anabar.blt`: Anabar constituency, 6 candidates, 2 seats, 553 voters.
- `nauru-2019-boe.blt`: Boe constituency, 5 candidates, 2 seats, 811 voters.

Nauru doesn't publish ballots. What is published (Wikipedia's "2019 Nauruan parliamentary
election", sourced to the Electoral Commission of Nauru) is how many voters put each candidate at
each rank, and each candidate's Dowdall score. Dowdall scoring depends only on those counts, so
any ballots that reproduce them must give the official scores. These files are such ballots,
built for the purpose: synthetic, not the voters' own. Each file's header gives the published
table and the scores the test expects.
