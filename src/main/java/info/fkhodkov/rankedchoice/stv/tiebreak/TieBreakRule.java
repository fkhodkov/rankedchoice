package info.fkhodkov.rankedchoice.stv.tiebreak;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Breaks a tie between candidates with equal tallies, deterministically. The same rule is
 * used both when several candidates are tied for lowest (who to eliminate first) and when
 * several are tied for highest and all meet quota in the same round (who to elect and
 * transfer first) — in both cases {@link #resolveTie} returns the one to act on first.
 */
public sealed interface TieBreakRule permits DeclaredOrderTieBreak {

    Candidate resolveTie(List<Candidate> tied);
}
