# pyRCV2 reference checks

Tools that check this project's STV against [pyRCV2](https://yingtongli.me/git/pyRCV2), an
independent STV implementation whose ERS97 count reproduces the ERS97 rulebook's model
election stage by stage (its own test). pyRCV2 is written for Transcrypt, but its counting code
runs as ordinary Python.

- `count.py` counts a BLT election with pyRCV2, under a preset matched to one of this
  project's rulesets: `ers97`, `gregory` (exact weighted inclusive Gregory, the default `stv`),
  `scottish` or `senate`. It fixes two pyRCV2 bugs on the way (stage results stored without copying, so
  its "backwards" tie-break never saw an earlier stage's votes) and prints every stage as JSON,
  or with `--sheet` as the count-sheet files `Ers97ModelElectionFixtureTest` reads
  (`library/src/test/resources/testdata/ers97/pyrcv2`).
- `compare.py` matches each round of this project's count to a pyRCV2 stage: every candidate's
  votes, the votes exhausted and the loss by fraction. `--exact` allows for this project's
  10-place reports of exact values; `--combined` compares exhausted plus lost as one total;
  `--tolerance X` allows differences up to X.

pyRCV2 is no longer developed: its last revision, 4dff302 (27 December 2025), adds notices that it's
deprecated in favour of OpenTally. That revision reproduces every count sheet in the fixtures
(`testdata/ers97/pyrcv2`, `testdata/aec/pyrcv2`) byte for byte (rechecked 8 October 2026).

```sh
git clone https://yingtongli.me/git/pyRCV2 pyrcv2
mvn package -DskipTests
java -jar cli/target/rankedchoice-cli-0.1.0-SNAPSHOT.jar -i election.blt --ruleset ers97 \
    --report-format json > ours.json
python3 tools/reference/pyrcv2/count.py pyrcv2 election.blt --preset ers97 > pyrcv2.json
python3 tools/reference/pyrcv2/compare.py ours.json pyrcv2.json
```

| Preset | This project | Compare with | Match in every round, of 100 random elections |
|---|---|---|---|
| `ers97` | `--ruleset ers97` | (no flags) | 99 |
| `gregory` | `--quota droop --transfer fractional --rounding exact --tiebreak previous-round,declared` | `--exact` | 100 |
| `scottish` | `--ruleset scottish` | `--combined` | 64; 100 within `--tolerance 0.01` |
| `senate` | `--ruleset senate` | (no flags) | 189 of 200; the rest differ only in loss by fraction, or crash pyRCV2 |

See [VALIDATION.md](../../../VALIDATION.md#the-ers97-model-election) for what the rest are.
