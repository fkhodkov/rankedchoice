# Australian Senate 2019, Tasmania

Election 24310: 44 candidates, 6 seats, 351,988 formal ballots. Checked by
`AecSenateFixtureTest`.

- `tas-2019-senate.blt.gz`: the ballots, from the AEC's published formal preference data for
  the election (aec-senate-formalpreferences-24310-TAS), as converted to BLT for pyRCV2's own
  tests (https://yingtongli.me/git/pyRCV2, tests/data). Identical ballots are merged.
- `tas-2019-senate.official.csv`: the AEC's official distribution of preferences
  (https://results.aec.gov.au/24310/Website/External/SenateStateDop-24310-TAS.pdf), as
  transcribed for the same pyRCV2 test: every candidate's votes, the exhausted votes and the
  loss by fraction at 42 of the 139 counts, each one where a step of the count begins. Columns
  come in pairs, votes and state (PEL provisionally elected, EL elected, EX excluded,
  EXCLUDING); the last two rows are exhausted votes and loss by fraction.
