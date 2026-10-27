"""Fetch a council's 2022 "Candidate Votes Per Stage" reports from its election results pages.

usage: fetch_reports.py OUT_DIR COUNCIL START_URL [START_URL ...]

COUNCIL is scot-elex's name for the council (e.g. sc_borders). Crawls up to two links deep from
each START_URL within the council's site, following links that mention wards, results or
downloads; every link that mentions the stage report and a ward number is saved, as a PDF
(following a download page to its file if need be), as OUT_DIR/COUNCIL_2022_wardN.pdf, the name
stage_report.py looks for. Requests are made one at a time.
"""
import os
import pathlib
import re
import sys
import time
import urllib.parse
import urllib.request

HEADERS = {"User-Agent": "Mozilla/5.0 (rankedchoice reference check)"}


def get(url):
    request = urllib.request.Request(urllib.parse.quote(url, safe=":/?=&%#"), headers=HEADERS)
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read(), response.headers.get("Content-Type", "")


def links(html, base):
    for href, text in re.findall(r'<a[^>]+href="([^"#]+)"[^>]*>(.*?)</a>', html, re.S):
        yield urllib.parse.urljoin(base, href.replace("&amp;", "&")), re.sub(r"<[^>]+>|\s+", " ", text).strip()


def pdf_from(url):
    """The PDF at url, or the first PDF a download page there links to."""
    data, _ = get(url)
    if data[:4] == b"%PDF":
        return data
    for href, _ in links(data.decode("utf-8", "replace"), url):
        if href.lower().split("?")[0].endswith(".pdf") or "/downloads/file/" in href and href != url:
            data, _ = get(href)
            if data[:4] == b"%PDF":
                return data
    return None


def main():
    out, council, starts = sys.argv[1], sys.argv[2], sys.argv[3:]
    os.makedirs(out, exist_ok=True)
    host = urllib.parse.urlparse(starts[0]).netloc.removeprefix("www.")
    queue, seen, saved = [(url, 0) for url in starts], set(starts), {}
    while queue:
        url, depth = queue.pop(0)
        try:
            data, kind = get(url)
        except Exception as e:  # noqa: BLE001 -- report and carry on
            print(f"{url}: {e}", flush=True)
            continue
        if "html" not in kind:
            continue
        for href, text in links(data.decode("utf-8", "replace"), url):
            label = f"{text} {urllib.parse.unquote(href)}".lower()
            # Falkirk's links name only the report; the ward is in the page's own address.
            ward = re.search(r"ward[\s_-]*(\d+)", label) or re.search(r"ward[\s_-]*(\d+)", url.lower())
            if "stage" in label and ward and int(ward.group(1)) not in saved:
                try:
                    pdf = pdf_from(href)
                except Exception as e:  # noqa: BLE001
                    print(f"{href}: {e}", flush=True)
                    continue
                if pdf:
                    n = int(ward.group(1))
                    pathlib.Path(out, f"{council}_2022_ward{n}.pdf").write_bytes(pdf)
                    saved[n] = href
                    print(f"ward {n}: {href}", flush=True)
                time.sleep(0.3)
            elif depth < 2 and href not in seen and host in urllib.parse.urlparse(href).netloc \
                    and re.search(r"ward|result|election|download", label):
                seen.add(href)
                queue.append((href, depth + 1))
        time.sleep(0.3)
    print(f"{council}: {len(saved)} reports")


if __name__ == "__main__":
    main()
