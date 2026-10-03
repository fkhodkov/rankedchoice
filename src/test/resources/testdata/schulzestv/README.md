# Schulze STV reference data

`tideman-a53.blt` is election A53 from Nicolaus Tideman's database: 460 voters, 10 candidates,
4 seats. It's the worked example in section 8.2 of Markus Schulze, "The Schulze Method of
Voting" (arXiv:1804.02973), which elects {a, d, g, j}. Many ballots are truncated, so
proportional completion matters. It was converted from `A53.dat` in Schulze's
`schulze3.zip` (http://m-schulze.9mail.de/schulze3.zip). Candidates a..j are named A..J.

`tideman-a53.supports.txt` is `A53_stv.dat` from the same archive, as published there (line
endings normalised). It's the output of Schulze's own program, `singl01g.cpp`. Each line is a
contest of 5 candidates, then for each one, `N[the other four; that one]` to 6 decimals.
`SchulzeStvTideman53FixtureTest` checks all 1,260 of them.
