# Reference checkers: their data

Each checker under `tools/reference/<name>/` checks one jurisdiction's official counts and takes
a data directory on its command line. `refresh_results.py DATA_ROOT` runs them all with
`DATA_ROOT/<name>/` as each one's directory, and skips a checker whose directory is missing. So
to refresh everything, make a directory per checker, named as below, and fill the ones that
don't fill themselves. Data isn't committed; each tool's README has the detail.

```
DATA_ROOT/
├── alameda/  sanfrancisco/  nyc/  alaska/  maine/      fetch their own data: create them empty
├── minneapolis/  eastpointe/  albany/  cambridge/      likewise
├── aec/          the AEC's Senate downloads, saved first
├── nsw/          nsw/download.py's files, saved first
├── portland/     the County's cast vote records, saved first
└── scotland/
    ├── scot-elex/              a clone of https://github.com/mggg/scot-elex
    ├── official.json           scotland/official.py's output
    ├── official-stages.json    the same output (the two names are from when they differed)
    └── reports/                scotland/fetch_reports.py's PDFs
```

## Checkers that fetch their own data

Create the directory empty; the first run downloads what it needs, and later runs reuse it
(`rcvcheck.fetch` keeps each file's address in the directory's `.sources.json`, and fetches a file
again only if it's asked for from another address, or with `refresh_results.py --refetch`).

| Directory | What it fetches | Needs | First run |
|---|---|---|---|
| `alameda/` | FairVote's records; the County's pass reports and Dominion XML | `pdftotext` | minutes |
| `sanfrancisco/` | FairVote's records; the Department's results, from the Internet Archive; the 2024 statement of vote | `pdftotext`, `soffice` | slow: the Archive rate-limits |
| `nyc/` | the Board's cast vote record zips (2021, 2023, 2025) and round pages | `soffice` | about 10 minutes; about 4 GB |
| `alaska/` | FairVote's records; the Division's reports, from the Internet Archive | `pdftotext` | minutes |
| `maine/` | FairVote's records; the State's reports and 2026 exports and first-choice tallies | `pdftotext`, `soffice` | minutes |
| `minneapolis/` | the City's cast vote records and summaries; FairVote's 2009 and 2013 records | `pdftotext`, `soffice` | minutes |
| `eastpointe/` | FairVote's records; the RCTab summaries on RCVis | | seconds |
| `albany/` | FairVote's records; the County's Dominion XML | | seconds |
| `cambridge/` | FairVote's records; the Civic Journal's count sheets | `pdftotext` | minutes |

## Checkers whose data is saved first

- `aec/`: each Senate election's distribution of preferences and each state's formal
  preferences, `SenateDopDownload-<event>.zip` and `aec-senate-formalpreferences-<event>-<state>.zip`
  (events 20499, 24310, 27966, 31496); `aec/README.md` has the download loop.
- `nsw/`: `python3 tools/reference/nsw/download.py LG2401 DATA_ROOT/nsw` saves every 2024 council
  count's ballot papers and distribution of preferences. NSW needs `openpyxl`: run
  `refresh_results.py` with a Python that has it (a virtual environment's), or NSW is skipped.
- `portland/`: the County's cast vote records, from Google Drive (linked from its turnout and
  statistics page), saved as `d1-cvr.csv` ... `d4-cvr.csv`; the summaries are fetched.
- `scotland/`: clone scot-elex as `scot-elex/`; run `scotland/official.py` (needs `openpyxl`) on the
  Electoral Management Board's 2022 results file, and save its output as both `official.json`
  and `official-stages.json`; fetch the councils' stage reports into `reports/` with
  `scotland/fetch_reports.py` (Falkirk, Scottish Borders, Highland). `scotland/README.md` has the
  commands.

## Programs

Java 25 and the project's jar (`mvn -o -q package -DskipTests`); `pdftotext` (poppler-utils);
LibreOffice's `soffice`; Python 3 with `openpyxl` for NSW and Scotland's `official.py`. A full
refresh takes about 45 minutes, most of it the Senate and Scotland; `--only` runs some checkers,
`--dry-run` shows what would run and what would be skipped, and `--compare` reruns without
writing anything.
