# OpenTally reference checks

Tools that check this project's STV rulesets against [OpenTally](https://yingtongli.me/git/OpenTally),
an independent STV counter (in Rust) with presets for many real rule sets.

- `count.py` counts a BLT election with OpenTally under a preset matched to one of this
  project's rulesets, and prints every stage of OpenTally's text report as JSON, in the form
  `tools/reference/pyrcv2/compare.py` reads. Presets: `gregory` (the default `stv`),
  `scottish`, `ers97`, `act`, `senate` (without OpenTally's early election, to compare with
  `senate` less `electAsTheyStand`), `tasmania` (Tasmania's Schedule 4, which OpenTally has no
  preset for, built from its options, for `hare-clark-qpq`), `nswlg` (for `nsw-lg`), `viclc` and
  `wa` (for `vic-lc` and `wa-lc`, without early election, like `senate`), and `meek06`/`meeknz`
  (for `meek`). With `--sheet`, a count sheet as `OpenTallyFixtureTest` reads
  (`src/test/resources/testdata/opentally`).
- `check.py` writes 200 seeded random elections (the same every time), counts each both ways and
  compares them round by round (for Meek, the winners only). It counts apart the elections that
  differ after a tie OpenTally broke at random, where this project uses declared order, and
  those OpenTally crashes on.

```sh
git clone https://yingtongli.me/git/OpenTally opentally
(cd opentally && cargo build --release)
mvn package -DskipTests
python3 tools/reference/opentally/check.py opentally/target/release/opentally work --preset act
```

Results, and what explains every remaining difference: see
[VALIDATION.md](../../../VALIDATION.md#opentally).
