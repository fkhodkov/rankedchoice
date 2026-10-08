# CPO-STV: a near-tie with a cycle

Checked by `CpoStvNearTieCycleFixtureTest`.

- `near-tie-cycle.blt`: synthetic, 8 candidates (R1–R4, B1–B4), 4 seats, 10,000 voters, generated
  with VoteKit's slate Plackett–Luce model:
  `python tools/votekit/generate.py tools/votekit/scenarios/near-tie.json out.blt --seed 5`.

CPO-STV has never been used in a public election, so there is no real count to check against.
This election was chosen because the head-to-head comparisons between committees form a cycle,
so CPO-STV elects a different committee from every classic STV variant. Its pairwise scores are
checked against an independent scorer, `tools/reference/cpo_stv.py`.
