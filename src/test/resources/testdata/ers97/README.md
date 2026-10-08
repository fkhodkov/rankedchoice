# ERS97: the rulebook's model election, and elections counted by pyRCV2

Checked by `Ers97ModelElectionFixtureTest`. Each file's header says where it comes from.

- `ers97-model-election.blt`: the Electoral Reform Society's worked model election from "How to
  conduct an election by the Single Transferable Vote" (3rd edition, the ERS97 rules): 11
  candidates, 6 seats, 753 valid votes. The ballots are pyRCV2's reconstruction
  (https://yingtongli.me/git/pyRCV2, `tests/data/ers97.blt`), with BLT's trailing "0" added to
  each ballot line.
- `ers97-model-election.rulebook.txt`: the rulebook's own count sheet for it, as transcribed in
  pyRCV2's `tests/data/ers97.csv`: every stage's votes, the non-transferable votes and the votes
  required.
- `pyrcv2/r127`, `r134`, `r148`, `ot11` (`.blt` and `.stages.txt`): four synthetic elections and
  pyRCV2's ERS97 count of each, made by `tools/reference/pyrcv2/count.py --preset ers97 --sheet`.
  The `r` elections are from that tool's random runs, `ot11` from `tools/reference/opentally`'s;
  the test's comment says what each exercises.

pyRCV2 revision 4dff302 (27 December 2025, its last: pyRCV2 is deprecated in favour of OpenTally)
reproduces all four `.stages.txt` files byte for byte (rechecked 8 October 2026).
