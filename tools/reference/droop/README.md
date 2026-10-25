# droop reference check

`check.py` checks Meek's and Warren's methods against [droop](https://github.com/jklundell/droop)
(commit cf58293), whose fixed-point arithmetic this project's counts follow, as
`MeekDroopReferenceTest` does for six stored reports, on 300 seeded random elections (3-10
candidates, 1-5 seats, a withdrawn candidate in a quarter of them, ballot lines of 1-20 voters or,
in a fifth, up to 2,000, rankings cut short, no tied ranks), each counted by Meek and by Warren at
9, 6, 12 and 13 decimal places.

Each of this project's rounds must be droop's next Elect or Defeat action (`meek|warren
arithmetic=fixed defeat_batch=none precision=N`), the same kind and, for an exclusion, the same
candidate; every tally droop's exactly; the residual this project's exhausted votes, less the
ballots that rank only withdrawn candidates (droop leaves those out of the count; this project
counts them as exhausted from the start, and either way they're out of the quota); an election's
surplus droop's vote less the quota; and the winners the same. This project reports figures to the
count's own places for the comparison (`--rounding truncate:N`): an exact report rounds them to 10.

A count droop stops on is reported apart. Rounding can leave an elected candidate a hair under the
quota, so that the elected candidates' votes less the quota sum below zero; droop then finds no
hopeful within that surplus of the lowest, not even the lowest, and stops on an empty tie
(`IndexError`). This project excludes the lowest there; droop with that line patched to
`max(E.surplus, V0)` matches it every round.

```sh
mvn package -DskipTests
python3 tools/reference/droop/check.py DROOP_DIR work
```

Results: see [VALIDATION.md](../../../VALIDATION.md#meek-and-warren).
