"""Collect the official winners of Scottish council elections into one JSON file.

usage: official.py EMB_2022.xlsx OUT.json [CACHE_DIR]   (needs openpyxl)

- 2022: the Electoral Management Board for Scotland's results file, the returning officers'
  returns for every ward with each candidate marked elected or not
  (https://www.emb.scot/downloads/file/980/local-government-elections-2022-results-updated-with-postals-station-split).
- 2007, 2012, 2017 (and 2022, as a cross-check): every council's election article on Wikipedia,
  whose STV result boxes list each ward's candidates, first preferences (count1) and, in bold,
  those elected. Fetched through the Wikipedia API and cached in CACHE_DIR (default wp/).

The output is a list of {year, source, ward, candidates: [{name, elected, first, counts}]}, where
counts (Wikipedia only) is the candidate's figure at each count as written, None where blank.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

import openpyxl

USER_AGENT = {"User-Agent": "rankedchoice-validation/0.1 (STV counting research)"}


def api(params):
    url = "https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode(
        {**params, "format": "json", "formatversion": 2})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=USER_AGENT), timeout=60) as r:
                return json.load(r)
        except Exception:
            time.sleep(3 + attempt * 5)
    raise RuntimeError(f"Wikipedia API failed: {url}")


def page(title):
    d = api({"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main",
             "titles": title, "redirects": 1})
    p = d["query"]["pages"][0]
    return None if "missing" in p else (p["title"], p["revisions"][0]["slots"]["main"]["content"])


def wikipedia_pages(cache):
    os.makedirs(cache, exist_ok=True)
    for year in ("2007", "2012", "2017", "2022"):
        _, text = page(f"{year} Scottish local elections")
        pattern = r"\[\[(" + year + r" [^\]|#]*?(?:[Cc]ouncil|Siar)[^\]|#]*? election)"
        for link in sorted(set(re.findall(pattern, text))):
            path = os.path.join(cache, f"{year}__{link.replace('/', '_')}.wiki")
            if not os.path.exists(path):
                result = page(link)
                if result is None:
                    continue
                open(path, "w").write(result[1])
                time.sleep(1)
            yield year, open(path).read()


def clean(value):
    value = re.sub(r"<ref[^>]*?/>|<ref.*?</ref>", "", value, flags=re.S)
    value = re.sub(r"<sup>.*?</sup>|[†‡*]", "", value, flags=re.S)
    value = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", value)
    return re.sub(r"\{\{[^{}]*\}\}", "", value)


def number(value):
    digits = re.sub(r"[^0-9.]", "", clean(value).replace("'''", ""))
    try:
        return float(digits) if digits else None
    except ValueError:
        return None


def figure(value):
    """A count's figure as written ("1,234.56" -> "1234.56"), or None where it's blank."""
    text = re.sub(r"[^0-9.]", "", clean(value).replace("'''", ""))
    return text if re.fullmatch(r"\d+(\.\d+)?", text) else None


def stv_boxes(year, text):
    for block in re.split(r"\{\{\s*STV Election box begin", text)[1:]:
        block = re.split(r"\{\{\s*STV Election box end", block)[0]
        # Template by template, not line by line: some pages put a whole candidate on one line.
        flat = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", block)
        flat = re.sub(r"<ref[^>]*?/>|<ref.*?</ref>", "", flat, flags=re.S)
        title = re.search(r"\|\s*title\s*=\s*([^\n|}]*)", flat)
        counts = re.search(r"\|\s*numcounts\s*=\s*(\d+)", flat)
        numcounts = int(counts.group(1)) if counts else 1
        candidates = []
        for chunk in re.split(r"\{\{\s*STV Election box candidate", flat)[1:]:
            chunk = re.sub(r"\{\{[^{}]*\}\}", "", chunk)
            params = dict(part.split("=", 1) for part in chunk.split("|") if "=" in part)
            params = {k.strip(): v.strip() for k, v in params.items()}
            raw = params.get("candidate", "")
            if raw:
                candidates.append({"name": clean(raw).replace("'''", "").replace("}}", "").strip(),
                                   "elected": "'''" in raw, "first": number(params.get("count1", "")),
                                   "counts": [figure(params.get(f"count{k}", "")) for k in range(1, numcounts + 1)]})
        if candidates:
            yield {"year": year, "source": "wikipedia", "ward": title.group(1).strip() if title else "?",
                   "candidates": candidates}


def emb_2022(path):
    rows = list(openpyxl.load_workbook(path, read_only=True, data_only=True)["RawDetails"].iter_rows(values_only=True))
    wards = {}
    for row in rows[1:]:
        if row[12]:
            wards.setdefault(f"{row[1]}: {row[2]}", []).append(
                {"name": row[12], "elected": row[13] == "Yes", "first": None})
    for ward, candidates in wards.items():
        yield {"year": "2022", "source": "emb", "ward": ward, "candidates": candidates}


if __name__ == "__main__":
    results = list(emb_2022(sys.argv[1]))
    for year, text in wikipedia_pages(sys.argv[3] if len(sys.argv) > 3 else "wp"):
        results += stv_boxes(year, text)
    json.dump(results, open(sys.argv[2], "w"), indent=0)
    print(len(results), "wards")
