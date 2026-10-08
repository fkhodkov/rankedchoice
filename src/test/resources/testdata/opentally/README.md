# OpenTally counts

Three of `tools/reference/opentally`'s seeded random elections (`check.py` writes them; these are
its e11, e154 and e170), each with OpenTally's count of it as a count sheet (`count.py --sheet`),
for the rulesets no published ballots can check: `vic-lc` against OpenTally's "Victorian
Legislative Council STV" preset and `wa-lc` against its "Western Australia STV" preset, both
without early election. Checked by `OpenTallyFixtureTest`, whose javadoc says what each
exercises.

- `<ruleset>-<election>.blt`: the ballots; `vic-lc-e11`, `vic-lc-e170`, `wa-lc-e11`, `wa-lc-e154`.
- `<ruleset>-<election>.stages.txt`: OpenTally's count of them under that ruleset's preset.

OpenTally: https://yingtongli.me/git/OpenTally, revision 3d4c415.
