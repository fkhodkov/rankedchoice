# STVPoll reference check

`check.py` checks this project's CPO-STV against [STVPoll](https://github.com/VoteIT/STVPoll)
0.5.6 (commit d6b68a7, VoteIT's Python library), the one implementation of CPO-STV written by
someone else that counts as Tideman's rules do: every candidate in neither outcome excluded, then
the surpluses of candidates in both transferred at their fraction, each outcome scored by its
members' votes.

On 300 seeded random elections (4-7 candidates, 2-4 seats, at most 35 outcomes) it compares, for
every pair of outcomes, the difference between their scores with STVPoll's `outcomes_duel` on the
same ballots and Droop quota, and the winners where STVPoll finds an outcome that beats every
other. STVPoll rounds each ballot's value to 5 places; here it's given exact rounding, so the rule
is what's compared.

The two count a comparison alike while the outcomes share at most one candidate. With two or more
shared they differ: this project transfers the largest pending surplus until none is left, STVPoll
gives each shared candidate one turn, in its set's order, and removes them. So those comparisons,
and a winner that differs only through them, are counted apart; their completions differ too
(STVPoll's minimax over the Smith set, this project's beatpath).

```sh
python3.12 -m venv sp && sp/bin/pip install ./STVPoll       # a checkout at d6b68a7
mvn package -DskipTests
sp/bin/python tools/reference/stvpoll/check.py work    # fixes its own PYTHONHASHSEED
```

Results: see [VALIDATION.md](../../../VALIDATION.md#cpo-stv).
