# Australian Senate, Tasmania, 2016 and 2019

Checked by `AecSenateFixtureTest`. Both come from the AEC's published formal preference data
(aec-senate-formalpreferences-<event>-TAS), with identical ballots merged.

## 2019

Election 24310: 44 candidates, 6 seats, 351,988 formal ballots.

- `tas-2019-senate.blt.gz`: the ballots, as converted to BLT for pyRCV2's own tests
  (https://yingtongli.me/git/pyRCV2, tests/data).
- `tas-2019-senate.official.csv`: the AEC's official distribution of preferences
  (https://results.aec.gov.au/24310/Website/External/SenateStateDop-24310-TAS.pdf), as
  transcribed for the same pyRCV2 test: every candidate's votes, the exhausted votes and the
  loss by fraction at 42 of the 139 counts, each one where a step of the count begins. Columns
  come in pairs, votes and state (PEL provisionally elected, EL elected, EX excluded,
  EXCLUDING); the last two rows are exhausted votes and loss by fraction.

## 2016

Election 20499, a double dissolution: 58 candidates, 12 seats, 339,159 formal ballots.

- `tas-2016-senate.blt.gz`: the ballots, converted by `tools/reference/aec/aec_to_blt.py`.
- `tas-2016-senate.dop.csv.gz`: the AEC's official distribution of preferences, all 355 counts,
  exactly as published in SenateDopDownload-20499.zip (SenateStateDOPDownload-20499-TAS.csv)
  less its last column, the comments.

## pyRCV2

- `pyrcv2/r134.blt`, `pyrcv2/r134.stages.txt`: a synthetic election (5 candidates, 4 seats),
  `tools/reference/pyrcv2/check.py`'s seeded random election 134 (`check.py ... --preset senate`
  writes it again, byte for byte), and pyRCV2's Senate count of it, made by
  `tools/reference/pyrcv2/count.py --preset senate --sheet`; VALIDATION.md's Senate section says
  why it's kept.
- `pyrcv2/r545.blt`, `pyrcv2/r545.stages.txt`: likewise election 545 (5 candidates, 3 seats; from
  `check.py ... --preset senate --elections 1000`), where pyRCV2 leaves an excluded candidate on
  -2/5 votes.

pyRCV2 revision 4dff302 (27 December 2025, its last) reproduces both count sheets byte for byte
(rechecked 27 October 2026).
