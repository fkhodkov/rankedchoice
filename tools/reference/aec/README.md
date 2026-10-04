# Australian Senate elections

Checks the `senate` ruleset against the AEC's official distribution of preferences for every
state and territory in the 2016 (event 20499), 2019 (24310), 2022 (27966) and 2025 (31496)
Senate elections.

- `aec_to_blt.py` converts a state's formal preference data
  (`aec-senate-formalpreferences-<event>-<state>.zip`, a row per ballot paper) into a BLT. A paper
  counts below the line if it numbers at least 6 candidates in sequence, otherwise above the
  line, each group expanded to its candidates in ballot order. For Tasmania 2019 the result is
  identical to pyRCV2's converted test data. 2016's files give each paper's marks as one field
  with no names, so the converter takes the candidates and groups from that election's
  distribution of preferences (`--dop`).
- `check.py` converts each state (caching the BLT next to the downloads), counts it with the
  `senate` ruleset and matches every round to one of the official counts
  (`SenateDopDownload-<event>.zip`), in order. Each round must match every candidate's votes, the
  exhausted votes and the loss by fraction exactly. It also compares the senators elected, in
  order, and prints how long each count took.

The downloads are linked from each election's CSV downloads page
(https://results.aec.gov.au/24310/Website/SenateDownloadsMenu-24310-Csv.htm, and the same for
each event). Each file is at `https://results.aec.gov.au/<event>/Website/External/<file>`.

```sh
mkdir aecdata && cd aecdata
for e in 20499 24310 27966 31496; do
  curl -sO -A Mozilla/5.0 https://results.aec.gov.au/$e/Website/External/SenateDopDownload-$e.zip
  for s in ACT NSW NT QLD SA TAS VIC WA; do
    curl -sO -A Mozilla/5.0 https://results.aec.gov.au/$e/Website/External/aec-senate-formalpreferences-$e-$s.zip
  done
done
cd .. && mvn package -DskipTests
python3 tools/reference/aec/check.py aecdata              # or e.g. 20499-TAS 31496-NSW
```

Result: see [VALIDATION.md](../../../VALIDATION.md#the-australian-senate).
