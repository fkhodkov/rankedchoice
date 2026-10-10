# NSW local government elections

Checks the `nsw-lg` ruleset against the NSW Electoral Commission's official distribution of
preferences for every councillor count of the 2024 ordinary council elections (event LG2401).

- `download.py` saves each council's (each ward's, where there are wards) ballot papers
  (`finalpreferencedatafile.zip`) and full distribution of preferences (`dopfulldetails.xlsx`)
  from the Commission's results site, one request at a time.
- `nsw_to_blt.py` converts a count to BLT: the Commission's own reading of each paper's marks
  and formality, groups above the line standing for their candidates in ballot order. A
  candidate elected mayor leaves the councillor count; the converter marks them withdrawn.
- `check.py` counts every one with `nsw-lg` and matches each round to an official count, in
  order: every candidate's votes and the votes lost (exhausted plus loss by fraction), to the
  vote, and the councillors elected. Counts that diverge only after a tie for exclusion the
  Commission decided by lot are counted apart.
- `nsw_table.py` writes a count's official figures as the CSV the test fixtures read
  (`library/src/test/resources/testdata/nsw`).

```sh
python3 tools/reference/nsw/download.py LG2401 lg2401
mvn package -DskipTests
python3 tools/reference/nsw/check.py lg2401          # needs openpyxl
```

Result: see [VALIDATION.md](../../../VALIDATION.md#real-elections).
