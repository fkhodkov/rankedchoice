# Plan: Java Ranked-Choice Voting Calculator (STV + Condorcet methods)

## 1. Scope

Build a Java library + CLI that:
- Tabulates **STV** elections under multiple rule variants (quota, transfer method, elimination/tie-break rules)
- Tabulates other ranked-choice methods, at minimum **Schulze** (Condorcet/beatpath), with IRV as a byproduct of the STV engine (single-winner STV = IRV)
- Is validated against **published, real-world results** and known synthetic test vectors, not just unit-tested against its own logic

Since the repo is currently empty, this is a from-scratch project.

## 2. Domain background (what "multiple variants" actually means)

STV isn't one algorithm — it's a family. The axes that matter and that the design must parameterize:

**Quota**
- Droop quota: `floor(votes / (seats+1)) + 1` (most common — used in Ireland, Scotland, Australia)
- Hare quota: `votes / seats`
- Hagenbach-Bischoff (no floor/+1)
- Imperiali quota (rare, larger-council)

**Surplus transfer method**
- Whole-vote / random transfer (historic, e.g. old US/Irish-by-hand counts) — non-deterministic, included mainly for historical fidelity, not for real use
- Fractional (Senatorial/Gregory) transfer — transfer surplus ballots at a fractional weight
- **Weighted Inclusive Gregory Method (WIGM)** — current Scottish local government standard, examines *all* a candidate's ballots each transfer, not just the last parcel
- **Meek method** — iterative "keep value" recalculation, correctly handles exhausted ballots and re-counts every ballot every round; needs convergence-loop implementation, not a single pass
- (Stretch) CPO-STV / Schulze-STV — compares whole pairs of outcomes; expensive, optional phase-4 item

**Elimination / tie-break rules**
- Single lowest-candidate elimination vs. batch elimination
- Tie-break by: previous round votes, original first-preference count, random draw (seeded, for reproducibility), or defined-order fallback

**Other ranked-choice methods**
- **Schulze** (beatpath / Condorcet-Schulze): pairwise matrix → strongest-path strengths → winner ordering. Deterministic, single-winner or ranked output.
- IRV falls out of the STV engine when `seats = 1`
- (Stretch) Ranked Pairs, Borda — same ballot model, easy to bolt on once ballot infra exists

This means the architecture needs a **shared ballot/election model** with **pluggable counting strategies**, not one hardcoded algorithm.

## 3. Architecture

```
rankedchoice/
├── core/
│   ├── model/          Ballot, Candidate, Election, RoundResult, ElectionResult
│   ├── ballot/         BallotParsers: BLT format, CSV/CVR, JSON
│   └── util/           fraction/rational arithmetic (BigDecimal or exact rationals)
├── stv/
│   ├── quota/          DroopQuota, HareQuota, HagenbachBischoffQuota (Strategy interface: Quota)
│   ├── transfer/       FractionalTransfer, WigmTransfer, MeekTransfer, WholeVoteTransfer (Strategy: TransferMethod)
│   ├── elimination/     tie-break strategies
│   └── StvCounter.java  orchestrates: config = {quota, transferMethod, tieBreak} -> runs rounds -> ElectionResult
├── condorcet/
│   ├── PairwiseMatrix.java
│   ├── SchulzeMethod.java
│   └── (stretch) RankedPairs.java
├── cli/
│   └── Main.java        picocli-based CLI: read ballots, choose method+variant, print round-by-round report
└── testdata/            known elections, expected outputs, source citations
```

Key design decisions:
- **Rational arithmetic**: STV surplus fractions must not accumulate floating-point error over many rounds — use exact fractions (`BigInteger`-backed `Fraction` class) or high-precision `BigDecimal` with a documented rounding rule matching the jurisdiction's official rule (e.g., Scottish rules truncate to 5 decimal places at a specific step — this detail matters for exact reproduction).
- **Immutable round snapshots**: each round produces an immutable `RoundResult` (tallies, transfers, eliminated/elected candidates) so the full count is auditable/printable, matching how official STV result sheets are published.
- **Strategy pattern** for quota/transfer/tie-break so a `StvConfig` object fully determines behavior — this is what "multiple variants" cashes out to in code.
- **Same `Ballot`/ranking model** feeds both STV and Schulze — a ranking is a ranking regardless of which tabulator consumes it.

## 4. Testing strategy — validate against known examples

This is the part that actually proves correctness, so it gets real weight in the plan:

1. **Synthetic worked examples** (hand-verifiable, small N):
   - Wikipedia's STV and Schulze worked examples (both have step-by-step tallies you can assert against)
   - The Meek method's own reference paper (Hill/Wichmann/Woodall, *Algorithm 123*) includes worked test cases — good for the Meek transfer implementation specifically since it's the most algorithmically distinct variant
   - Schulze's original paper's example matrix

2. **Reference implementation cross-check**:
   - **OpenSTV** (open-source Python STV tool) ships a large suite of `.blt` test elections with known outcomes across multiple rule variants — use these as golden test vectors (support the `.blt` format in the parser specifically so these drop in directly). Don't copy its code — just its test election files and expected winners, cited.

3. **Real published official counts**:
   - Scottish council elections (WIGM) — councils publish full stage-by-stage transfer sheets; pick a handful of small wards for exact reproduction
   - Cambridge, MA municipal elections — STV since 1941, official round-by-round results published
   - Australian Senate / Irish Dáil counts — good Droop-quota/fractional-transfer references (large ones are heavy but useful for stress-testing performance)
   - Debian project leader / TC elections — use Condorcet/Schulze and publish full pairwise matrices and results, ideal Schulze validation source
   - Single-winner US RCV (San Francisco, Minneapolis) — validates the IRV degenerate case of the STV engine

4. **Test harness shape**:
   - Each fixture = ballot file + config (quota/transfer/seats) + expected result (elected set, and ideally full round-by-round tallies) + citation/source URL in a comment
   - JUnit5 parameterized tests iterate the fixture directory so adding a new known election is "drop a file in `testdata/`", not "write new test code"
   - Separate fast unit tests (hand-built small cases, edge cases: ties, exhausted ballots, all-but-one-eliminated) from the slower golden-fixture suite

5. **Edge cases to explicitly cover**: exhausted ballots, ties at threshold, more candidates eliminated than remaining seats, single-candidate/single-seat degenerate cases, malformed ballots (skipped/repeated rankings), unanimous first-preference winner (no transfers needed).

## 5. Tooling

- **Java 21**, Maven or Gradle (Gradle if you want multi-module convenience) — your call, no strong reason to prefer one here
- JUnit 5 + AssertJ for tests
- picocli for the CLI
- No heavy external election-specific dependency exists worth pulling in — this is small enough to build directly

## 6. Milestones

| Phase | Deliverable |
|---|---|
| 1 | Core ballot model + BLT/CSV parsers + `Fraction` arithmetic util, unit-tested standalone |
| 2 | STV engine with Droop quota + fractional (Gregory) transfer only — simplest variant, validate against Wikipedia + OpenSTV small fixtures |
| 3 | Add Hare/Hagenbach-Bischoff quotas, WIGM transfer, tie-break strategies — validate against Scottish council fixtures |
| 4 | Meek method (iterative engine, different loop shape than the others) — validate against Hill/Wichmann/Woodall paper cases |
| 5 | Schulze method (pairwise matrix + beatpath) — validate against Debian election fixtures and Wikipedia example |
| 6 | CLI polish: pick method+variant via flags, round-by-round report output (text/JSON), README documenting supported variants and citing test sources |
| 7 (stretch) | IRV explicit wrapper, Ranked Pairs, CPO-STV/Schulze-STV |

## 7. Open questions worth deciding before coding

- Exact rational arithmetic vs. fixed-precision decimal matching a specific jurisdiction's official rounding rule — affects whether you can reproduce official results *exactly* or just "materially the same winner." Recommend: exact fractions internally, with a configurable rounding/truncation step to match specific official rules when needed for exact reproduction.
- Multi-module (separate `stv`/`condorcet` artifacts) vs. single module — only matters if you want to publish the core ballot model independently later.
