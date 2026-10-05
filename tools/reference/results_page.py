"""Build the results page from the checkers' results and the jurisdictions file.

usage: results_page.py [--out PATH]

Reads results/jurisdictions.json (each jurisdiction's name, what it is checked against, the figures of those no
checker here writes, the known differences and the US rules table) and results/<checker>.json, which each US
checker writes with --results (`python3 tools/reference/nyc/check.py nyc-data --results
tools/reference/results/nyc.json`), and writes results/real-elections.html, a self-contained page with
results/page.css inlined. A contest that doesn't match every round, or elects differently, and that no difference
in the jurisdictions file names is shown as unexplained, and so is reported here; a difference naming a contest
that now matches is reported too.
"""
import argparse
import html
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")


def esc(text):
    return html.escape(str(text), quote=True)


def number(n):
    return f"{n:,}"


def load_checker(name):
    with open(os.path.join(RESULTS, name + ".json"), encoding="utf-8") as source:
        return json.load(source)


def is_exact(contest):
    return contest["rounds"] is not None and contest["matched"] == contest["rounds"]


def is_short(contest):
    """Not exact where rounds are compared, or electing differently."""
    return (contest["rounds"] is not None and contest["matched"] < contest["rounds"]) or not contest["winners_same"]


def figures(jurisdiction, checked):
    """contests, every_round, with_rounds, winners_same and rounds_missed for a jurisdiction."""
    if "fixed" in jurisdiction:
        return dict(jurisdiction["fixed"], rounds_missed=None)
    contests = checked[jurisdiction["checker"]]["contests"]
    with_rounds = [c for c in contests if c["rounds"] is not None]
    return {"contests": len(contests), "every_round": sum(map(is_exact, with_rounds)), "with_rounds": len(with_rounds),
            "winners_same": sum(c["winners_same"] for c in contests),
            "rounds_missed": sum(c["rounds"] - c["matched"] for c in with_rounds),
            "rounds_total": sum(c["rounds"] for c in with_rounds)}


def result_cell(jurisdiction, f, single_seat):
    if "chip" in jurisdiction:
        kind = "exact" if f["winners_same"] == f["contests"] and f["every_round"] in (None, f["contests"]) else (
            "plain" if f["every_round"] is None else "near")
        return f'<span class="chip {kind}">{esc(jurisdiction["chip"])}</span><p>{esc(jurisdiction["text"])}</p>'
    if single_seat:
        share = 100 * f["every_round"] / f["contests"]
        return (f'<div class="meter"><span class="bar"><i style="width: {share:.1f}%"></i></span>'
                f'<span class="mono">{f["every_round"]} / {f["contests"]}</span></div>'
                f'<p>Every round in {number(f["every_round"])}; eliminations and winner in '
                f'{"all " + number(f["contests"]) if f["winners_same"] == f["contests"] else number(f["winners_same"])}.</p>')
    precision = jurisdiction.get("precision", "")
    if f["rounds_missed"] is None:    # figures from VALIDATION.md, every round matched
        return f'<span class="chip exact">Every round</span><p>{esc(precision[0].upper() + precision[1:])}.</p>'
    if f["rounds_missed"] == 0:
        chip = '<span class="chip exact">Every round</span>'
    else:
        chip = f'<span class="chip near">Every round but {f["rounds_missed"]}</span>'
    text = f'{number(f["rounds_total"])} official rounds, {precision}.'
    if f["with_rounds"] < f["contests"]:
        text += f' {f["contests"] - f["with_rounds"]} checked by winners only.'
    return chip + f"<p>{esc(text)}</p>"


def register(data, checked):
    rows = []
    for group in data["groups"]:
        rows.append(f'<tr class="group"><td colspan="5">{esc(group["name"])}</td></tr>')
        for j in group["jurisdictions"]:
            f = figures(j, checked)
            ruleset = f'<code>{esc(j["ruleset"])}</code>' if j["ruleset"] else "—"
            rows.append(
                f'<tr><td class="place">{esc(j["name"])}<small>{esc(j["years"])}</small></td>'
                f'<td class="num">{number(f["contests"])}</td><td>{ruleset}</td><td class="what">{esc(j["against"])}</td>'
                f'<td><div class="result">{result_cell(j, f, group.get("single_seat", False))}</div></td></tr>')
    return "\n".join(rows)


def differences(data, checked):
    """The cause cards, the unexplained contests among them, and warnings for the generator's caller."""
    named = {(d.get("checker"), c) for d in data["differences"] for c in d.get("contests", [])}
    warnings, unexplained = [], []
    for name, results in checked.items():
        by_id = {c["contest"]: c for c in results["contests"]}
        for c in results["contests"]:
            if is_short(c) and (name, c["contest"]) not in named:
                unexplained.append(f'{name} {c["contest"]}: {c["line"]}')
        for checker, contest in named:
            if checker == name and contest in by_id and not is_short(by_id[contest]) \
                    and not any(d["cause"] == "source" for d in data["differences"] if contest in d.get("contests", [])):
                warnings.append(f"{name} {contest} is named as a difference but now matches every round")
            if checker == name and contest not in by_id:
                warnings.append(f"{name} {contest} is named as a difference but isn't among the checker's contests")
    cards = []
    for cause in data["causes"]:
        items = [d["text"] for d in data["differences"] if d["cause"] == cause["id"]]
        cards.append(f'<div class="cause"><h3>{esc(cause["title"])}</h3><p class="why">{esc(cause["why"])}</p>'
                     f'<ul>{"".join(f"<li>{esc(i)}</li>" for i in items)}</ul></div>')
    if unexplained:
        cards.append('<div class="cause unexplained"><h3>Not yet explained</h3>'
                     '<p class="why">Contests the checkers report short of every round, with no cause recorded.</p>'
                     f'<ul>{"".join(f"<li>{esc(u)}</li>" for u in unexplained)}</ul></div>')
    return "\n".join(cards), unexplained, warnings


def example(data):
    ex = data["example"]
    official = {r["ours"]: r["official"] for r in ex["rounds"]}
    columns = range(0, ex["our_rounds"] + 1)
    top = "".join(f'<span class="cell on">{official[k]}</span>' if k in official else '<span class="cell"></span>'
                  for k in columns)
    links = "".join('<span class="cell on"></span>' if k in official else '<span class="cell"></span>' for k in columns)
    ours = "".join(f'<span class="cell{" hit" if k in official else ""}">{"1st" if k == 0 else k}</span>'
                   for k in columns)
    described = "; ".join(f'official round {r["official"]} matches '
                          + ("the record's first choices" if r["ours"] == 0 else f'this count\'s round {r["ours"]}')
                          for r in ex["rounds"])
    cards = "".join(f'<div><b>{esc(r["label"])}</b><span>{esc(r["text"])}</span>'
                    f'<span class="mono">{esc(r["figures"])}</span>'
                    + (f'<span>{esc(r["after"])}</span>' if r.get("after") else "") + "</div>" for r in ex["rounds"])
    return (f'<section aria-labelledby="example-h"><header><h2 id="example-h">{esc(ex["title"])}</h2>'
            f'<p>{esc(ex["intro"])}</p></header><div class="example">'
            f'<div class="lanes" role="img" aria-label="{esc(described.capitalize())}."><div class="lane-grid">'
            f'<div class="official"><span class="lane-name">Official rounds</span>{top}</div>'
            f'<div class="link-row"><span class="lane-name"></span>{links}</div>'
            f'<div class="ours"><span class="lane-name">This count</span>{ours}</div>'
            f'</div></div><div class="figures">{cards}</div></div></section>')


def rules(data):
    head = "".join(f"<th>{esc(c)}</th>" for c in data["rules"]["columns"])
    body = []
    for row in data["rules"]["rows"]:
        *cells, options = row
        option = "its own converter" if options is None else (f"<code>{esc(options)}</code>" if options else "defaults")
        body.append("<tr>" + "".join(f"<td>{esc(c)}</td>" for c in cells) + f'<td class="opt">{option}</td></tr>')
    return f"<thead><tr>{head}</tr></thead><tbody>{''.join(body)}</tbody>"


def build(data, checked, css, stamp):
    totals = [figures(j, checked) for g in data["groups"] for j in g["jurisdictions"]]
    contests = sum(f["contests"] for f in totals)
    winners = sum(f["winners_same"] for f in totals)
    jurisdictions = len(totals)
    cards, unexplained, warnings = differences(data, checked)
    differ = contests - winners
    exception = (f'{number(differ)} contest{"" if differ == 1 else "s"} elect{"s" if differ == 1 else ""} differently: '
                 "see Known differences. ") if differ else ""
    page = f"""<title>Real Elections Recounted</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wdth,wght@75..100,500..800&family=Public+Sans:ital,wght@0,400;0,600;1,400&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
{css}</style>
<!-- Generated by tools/reference/results_page.py from tools/reference/results; edit those, not this file. -->
<div class="wrap">

<header class="masthead">
  <div>
    <div class="label">rankedchoice · validation against official counts</div>
    <h1>Real elections, recounted from their ballots</h1>
    <p class="lede">The calculator is run on the published ballots of real elections, under each jurisdiction's own rules, and every official round is compared with its count: candidate by candidate, to the precision the official count gives.</p>
  </div>
  <div>
    <div class="tally">
      <div><strong>{number(contests)}</strong><span>contests in {jurisdictions} jurisdictions</span></div>
      <div><strong>{number(winners)}</strong><span>elect the official winners</span></div>
    </div>
    <p class="stamp">{esc(exception)}{esc(stamp)}</p>
  </div>
</header>

<section aria-labelledby="register-h">
  <header>
    <h2 id="register-h">Jurisdictions</h2>
    <p>"Every round" means every candidate's votes in every official round, at the official precision. Where a contest falls short, the reason is under <a href="#differences">Known differences</a>.</p>
  </header>
  <div class="register">
    <table>
      <thead>
        <tr><th>Jurisdiction</th><th class="num">Contests</th><th>Ruleset</th><th>Checked against</th><th>Result</th></tr>
      </thead>
      <tbody>
{register(data, checked)}
      </tbody>
    </table>
  </div>
</section>

{example(data)}

<section id="differences" aria-labelledby="differences-h">
  <header>
    <h2 id="differences-h">Known differences</h2>
    <p>Every figure that doesn't match, by cause. In each the same candidates are eliminated, in the same order, and the same candidates win, unless it says otherwise.</p>
  </header>
  <div class="causes">
{cards}
  </div>
</section>

<section class="rules" aria-labelledby="rules-h">
  <header>
    <h2 id="rules-h">How US ranked-choice counts read ballots</h2>
    <p>The rules that differ between the US counts, each found by matching the official rounds. One converter reads them all, one option per rule; Portland's ballot record has a different shape and its own converter.</p>
  </header>
  <div class="register">
    <table>
{rules(data)}
    </table>
  </div>
</section>

<footer class="notes">
  <p>Ballots come from the jurisdictions themselves, from FairVote's collections of cast vote records (Harvard Dataverse), and for Scotland from MGGG's scot-elex. Official results come from each election authority's own reports, or the Internet Archive's copies where the authority no longer publishes them.</p>
  <p>The full evidence for every figure, and the scripts that reproduce each check, are in the project's VALIDATION.md and tools/reference.</p>
</footer>

</div>
"""
    return page, unexplained, warnings


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", default=os.path.join(RESULTS, "real-elections.html"))
    args = parser.parse_args()
    with open(os.path.join(RESULTS, "jurisdictions.json"), encoding="utf-8") as source:
        data = json.load(source)
    with open(os.path.join(RESULTS, "page.css"), encoding="utf-8") as source:
        css = source.read()
    names = [j["checker"] for g in data["groups"] for j in g["jurisdictions"] if "checker" in j]
    checked = {name: load_checker(name) for name in names}
    newest = max(results["date"] for results in checked.values())
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=HERE, capture_output=True, text=True).stdout.strip()
    stamp = (f"US checks run up to {newest}" + (f", at commit {commit}" if commit else "")
             + "; the other figures are from VALIDATION.md.")
    page, unexplained, warnings = build(data, checked, css, stamp)
    with open(args.out, "w", encoding="utf-8") as out:
        out.write(page)
    for line in unexplained:
        print(f"unexplained: {line}", file=sys.stderr)
    for line in warnings:
        print(f"warning: {line}", file=sys.stderr)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
