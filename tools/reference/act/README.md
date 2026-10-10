# ACT election data

Tools that turn Elections ACT's published data into this project's test fixtures:

- `act_to_blt.py` converts the ballot paper preference data for one electorate to BLT.
- `act_table.py` extracts the official distribution of preferences into the plain-text
  expected file `ActOfficialDistributionTest` reads.

## The 2020 election

Elections ACT's site blocks automated downloads, so these come from the Wayback Machine (`id_`
after the timestamp returns the file itself):

```sh
mkdir act2020 && cd act2020
A=http://web.archive.org/web/20240331203743id_/https://www.elections.act.gov.au/__data/assets/text_file
for f in 0006/1662063/Candidates.txt 0007/1662064/Electorates.txt 0010/1662067/Groups.txt \
         0005/1662062/BrindabellaTotal.txt 0009/1662066/GinninderraTotal.txt 0003/1662069/KurrajongTotal.txt \
         0009/1662075/MurrumbidgeeTotal.txt 0009/1662084/YerrabiTotal.txt; do
  curl -sLO "$A/$f"
done
T=https://www.elections.act.gov.au/elections_and_voting/2020_legislative_assembly_election/distribution-of-preferences-2020
for e in Brindabella Ginninderra Kurrajong Murrumbidgee Yerrabi; do
  curl -sL -o table2_$e.xlsx "http://web.archive.org/web/20240331203731id_/$T/table2_$e.xlsx"
done
cd ..
```

Then, for each electorate (`act_table.py` needs `openpyxl`):

```sh
python3 tools/reference/act/act_to_blt.py act2020 Yerrabi library/src/test/resources/testdata/act/act-2020-yerrabi.blt
python3 tools/reference/act/act_table.py act2020/table2_Yerrabi.xlsx \
    library/src/test/resources/testdata/act/act-2020-yerrabi.blt \
    library/src/test/resources/testdata/act/act-2020-yerrabi.official.txt
```

Every round of the `act-hare-clark` count of all five electorates matches an official count
exactly; see [VALIDATION.md](../../../VALIDATION.md#real-elections).
