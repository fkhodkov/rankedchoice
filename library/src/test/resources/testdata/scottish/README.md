# Scotland: council wards, 2012 to 2022

Real ballots, each checked under the `scottish` ruleset.

| Files | Ward | Source of the ballots | Checked against | Test |
|---|---|---|---|---|
| `highland-2022-badenoch-and-strathspey` `.blt`, `.expected.txt` | Highland 2022, Badenoch and Strathspey | Highland Council's Preference Profile report | the council's Candidate Votes Per Stage report, transcribed | `BadenochAndStrathspeyFullReplayTest` |
| `falkirk-2022-ward4-carse-kinnaird-and-tryst` `.blt`, `.stages.txt` | Falkirk 2022, ward 4 | scot-elex `blt_files/7_cands/falkirk_2022_ward4.blt` | the council's stage report | `ScottishStageReportFixtureTest` |
| `scottish-borders-2022-ward3-galashiels-and-district` `.blt`, `.stages.txt` | Scottish Borders 2022, ward 3 | scot-elex `blt_files/9_cands/sc_borders_2022_ward3.blt` | the council's stage report | `ScottishStageReportFixtureTest` |
| `north-lanarkshire-2017-ward11-coatbridge-south.blt` | North Lanarkshire 2017, ward 11 | scot-elex `blt_files/8_cands/north_lanarkshire_2017_ward11.blt` | first preferences and winners, as declared | `ScottishRealElectionFixtureTest` |
| `orkney-2022-ward1-kirkwall-east.blt` | Orkney 2022, ward 1 | scot-elex `5_cands/orkney_2022_ward1.csv` | first preferences and winners, as declared; some rounds against the council's stage report | `ScottishRealElectionFixtureTest` |
| `shetland-2012-ward5-south.blt`, `shetland-2017-ward2-north.blt` | Shetland 2012 ward 5, 2017 ward 2 | scot-elex `4_cands/` CSVs | first preferences and winners, as declared; for 2017 ward 2, some rounds against the council's stage report | `ScottishRealElectionFixtureTest` |
| `orkney-2012-ward6-north-isles.blt` | Orkney 2012, ward 6 | scot-elex `4_cands/orkney_2012_ward6.csv` | Wikipedia's results table for the ward | `OrkneyNorthIslesFixtureTest` |

scot-elex is MGGG's archive of Scottish preference profiles (https://github.com/mggg/scot-elex,
described in arXiv:2605.12676). Its BLT files are converted by `tools/reference/scotland/scot_elex.py`
(names quoted, parties dropped); the CSV-derived files' headers say how they were converted. The
Falkirk and Scottish Borders files have no header: `scot_elex.py` on scot-elex revision de9f8e3
gives both byte for byte (rechecked 8 October 2026). Their `.stages.txt` files are the councils'
Candidate Votes Per Stage reports (PDFs), read by `tools/reference/scotland/stage_report.py`;
`tools/reference/scotland` checks 40 such reports in all. The other files' headers give their
sources and the official figures.
