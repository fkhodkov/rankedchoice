# QPQ reference check

`check.py` counts random elections by Woodall's Quota Preferential by Quotient three ways, with
this project, [droop](https://github.com/jlundell/droop)'s `qpq` rule (commit cf58293) and
[OpenSTV](https://github.com/Conservatory/openstv) 1.7's `QPQ` (commit 2a267a4), and compares every
decision: each election of the candidate with the highest quotient over the quota and each
exclusion of the one with the lowest must be the next decision of both others, the same
candidate at the same quota and quotient (to 10^-8: droop reports 9 places, the others 10), each
restart after the first count must be one OpenSTV restarts at, and the winners must be the same.

- `check.py` writes 1,000 seeded random elections (the same every time: 3-9 candidates, 1-5
  seats, a withdrawn candidate in a quarter, 5-60 ballot lines of weight 1-9, rankings cut short,
  no tied ranks), counts each three ways and reports the elections that differ. Ties are broken by
  candidate order in all three (`--tiebreak declared`; droop always; OpenSTV `-t index`, with no
  look back), and a difference at a tied decision is counted apart.
- `openstv_qpq.py` runs OpenSTV's QPQ on one election under Python 2 and prints its rounds as JSON:
  each round's threshold, decision, chosen candidate's quotient and whether it restarted.

The counts end differently, which isn't compared: this project and droop elect the hopefuls left
once there are no more than seats, OpenSTV counts on until they reach the quota.

```sh
mvn package -DskipTests
python3 tools/reference/qpq/check.py DROOP_DIR OPENSTV_DIR work --python2 PATH_TO_PYTHON2
```

Results: see [VALIDATION.md](../../../VALIDATION.md#qpq).
