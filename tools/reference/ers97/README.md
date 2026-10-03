# ERS97 reference checks

Tools that check this project's `ers97` ruleset against
[pyRCV2](https://yingtongli.me/git/pyRCV2), an independent STV implementation whose ERS97
count reproduces the ERS97 rulebook's model election stage by stage (its own test). pyRCV2 is
written for Transcrypt, but its counting code runs as ordinary Python.

- `pyrcv2_ers97.py` counts a BLT election with pyRCV2's ERS97 counter, configured as its
  model-election test configures it, ties broken as this project's `ers97` breaks them. It
  prints every stage as JSON, or with `--sheet` as the count-sheet files
  `Ers97ModelElectionFixtureTest` reads (`src/test/resources/testdata/ers97/pyrcv2`).
- `compare.py` matches each round of this project's count to a pyRCV2 stage.

```sh
git clone https://yingtongli.me/git/pyRCV2 pyrcv2
mvn package -DskipTests
java -jar target/rankedchoice-0.1.0-SNAPSHOT.jar -i election.blt --ruleset ers97 --report-format json > ours.json
python3 tools/reference/ers97/pyrcv2_ers97.py pyrcv2 election.blt > pyrcv2.json
python3 tools/reference/ers97/compare.py ours.json pyrcv2.json
```

On 100 random elections (4–13 candidates, up to 1,000 voters), 98 match in every round; see
[VALIDATION.md](../../../VALIDATION.md#the-ers97-model-election) for the other two.
