# Meek and Warren: MapLibre's 2025 board election, and droop's counts

- `maplibre-governing-board-2025.blt`: the MapLibre Governing Board election of 2025, a real
  Meek STV election run on OpaVote: 6 candidates, 5 seats, 92 ballots (89 valid, 3 empty). From
  MapLibre's mirror of the ballots (github.com/maplibre/maplibre, `2025-polls/`), which also
  holds OpaVote's results PDF: the winners and every round's keep factors to 6 places. Checked
  by `MeekMapLibreGoverningBoard2025FixtureTest`; the file's header has the links.

`droop/` holds reports from droop (github.com/jklundell/droop, v0.14, revision cf58293), made
with `python3 Droop.py meek arithmetic=fixed defeat_batch=none FILE.blt` (or `warren` in place
of `meek` for the `.warren.droop.txt` files). `MeekDroopReferenceTest` matches every round to
them, to droop's 9 decimal places.

- `maplibre-governing-board-2025.droop.txt`, `.warren.droop.txt`: the election above.
- `synthetic-tiebreak`: an exclusion needs droop's tie-break.
- `synthetic-stable`: keep-factor iteration stops because the surplus stopped shrinking.
- `synthetic-warren-differs`: Meek and Warren elect different committees (C8 under Meek, C2
  under Warren); reports for both.

The three synthetic elections are random: `synthetic-tiebreak` is seed 24 of a batch made with
`random.Random(seed)` (3–9 candidates, 1–4 seats, 5–40 ballot lines of 1–20 voters, odd seeds
with ties), and `synthetic-stable` and `synthetic-warren-differs` are seeds 1046 and 1014 of a
second batch (`random.Random(1000 + seed)`, 4–10 candidates, larger counts from seed 30). The
generators weren't kept as tools, but they're in the session that made these files, and
re-running them on 8 October 2026 gave all three byte for byte; only the title line was renamed.
