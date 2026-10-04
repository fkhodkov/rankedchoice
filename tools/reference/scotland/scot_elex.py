"""Convert one of MGGG's scot-elex BLT files into a BLT this project reads.

usage: scot_elex.py IN.blt OUT.blt

scot-elex (https://github.com/mggg/scot-elex) holds David McCune's ballot files for Scottish
council elections. Their candidate lines aren't quoted, and may hold the party as well as the
name, on either side ('"Lab" "Barney CROCKETT"', '"Gill AL-SAMARAI" "Scottish National Party
(SNP)"', '# ALTERNATIVE NAME 1: Nina Baker'); some files separate numbers with commas. This
writes the ballots unchanged and each candidate's name alone, quoted.
"""
import re
import sys


def candidate_name(line):
    """The name in a candidate line: the piece with a given name and a capitalised surname."""
    line = re.sub(r"^#\s*ALTERNATIVE NAME \d+:\s*", "", line.strip())
    pieces = [p.strip() for p in re.split(r'"+', line) if p.strip()]

    def is_name(piece):
        words = re.sub(r"[(].*?[)]", " ", piece).split()
        return (any(w.isupper() and len(re.sub(r"[^A-Z]", "", w)) >= 2 for w in words)
                and any(not w.isupper() and w[:1].isupper() for w in words))

    names = [p for p in pieces if is_name(p)]
    # Otherwise: a piece of two or more words without a party's parentheses (not "Lab").
    plain = sorted((p for p in pieces if "(" not in p), key=lambda p: len(p.split()) < 2)
    name = names[0] if names else plain[0] if plain else pieces[0] if pieces else line
    return re.sub(r"\s*\([^)]*\)\s*$", "", name).replace('"', "'").strip()


def convert(text):
    """(BLT text, candidate names, seats, ward name)."""
    lines = text.replace("\r", "").split("\n")

    def numbers(line):
        return line.replace(",", " ").split()

    n, seats = map(int, numbers(lines[0])[:2])
    end = next(i for i, line in enumerate(lines) if i > 0 and numbers(line) == ["0"])
    out = [" ".join(numbers(line)) for line in lines[:end + 1]]
    names = [candidate_name(line) for line in lines[end + 1:end + 1 + n]]
    out += ['"' + name + '"' for name in names]
    ward = next((line.strip().strip('"') for line in lines[end + 1 + n:] if line.strip()), "")
    out.append('"' + ward.replace('"', "'") + '"')
    return "\n".join(out) + "\n", names, seats, ward


if __name__ == "__main__":
    with open(sys.argv[2], "w") as out:
        out.write(convert(open(sys.argv[1], encoding="utf-8", errors="replace").read())[0])
