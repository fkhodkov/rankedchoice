"""Count a BLT election by OpenSTV's QPQ and print its rounds as JSON. Runs under Python 2, as OpenSTV does.

usage: python2 openstv_qpq.py OPENSTV_DIR ELECTION.blt

OPENSTV_DIR is a checkout of OpenSTV 1.7 (https://github.com/Conservatory/openstv, commit 2a267a4).
Ties are broken by candidate index alone (-t index -w strong: no look back through earlier rounds).
Prints {"rounds": [{"threshold", "action": [elect|exclude, name], "quotient" (the chosen
candidate's), "restart"}], "winners": [...]}, values as OpenSTV formats them.
"""
import json
import sys

sys.path.insert(0, sys.argv[1])
from openstv.ballots import Ballots  # noqa: E402
from openstv.plugins import getMethodPlugins  # noqa: E402
from openstv.qx import QX  # noqa: E402

dirty = Ballots()
dirty.loadKnown(sys.argv[2], exclude0=False)
ballots = dirty.getCleanBallots()
e = getMethodPlugins("byName", exclude0=False)["QPQ"](ballots)
e.strongTieBreakMethod = "index"
e.weakTieBreakMethod = "strong"
e.runElection()

names = ballots.names
rounds = []
for r in range(e.numRounds):
    kind, chosen = e.roundInfo[r].get("action", (None, []))
    rounds.append({
        "threshold": QX.str(e.thresh[r]),
        "action": [{"surplus": "elect", "eliminate": "exclude"}.get(kind, kind), names[chosen[0]] if chosen else None],
        "quotient": QX.str(e.count[r][chosen[0]]) if chosen else None,
        "restart": "Restart count" in e.msg[r],
    })
print(json.dumps({"rounds": rounds, "winners": [names[c] for c in sorted(e.winners)]}))
