#!/usr/bin/env python3
"""Turn a Project Gutenberg book into the reader's format, plus title/author and cover.

Format: paragraphs separated by one blank line, unwrapped; a paragraph
starting with "# " is a heading (gets its own page).

usage: prep_gutenberg.py <gutenberg-id> <name> [--cover]   -> books/<name>.txt, .meta, .jpg
"""
import io
import re
import subprocess
import sys
import zipfile
import urllib.request
from pathlib import Path


def fetch(book_id):
    url = f"https://www.gutenberg.org/cache/epub/{book_id}/pg{book_id}.txt"
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read().decode("utf-8-sig")


def body(raw):
    raw = raw.replace("\r", "")
    start = re.search(r"^\*\*\* ?START OF.*\*\*\*$", raw, re.M)
    end = re.search(r"^\*\*\* ?END OF.*\*\*\*$", raw, re.M)
    return raw[start.end() if start else 0 : end.start() if end else len(raw)]


def is_heading(p):
    letters = [c for c in p if c.isalpha()]
    return "\n" not in p and len(p) < 60 and letters and all(c.isupper() for c in letters)


def convert(raw):
    paras = [p.strip() for p in re.split(r"\n\s*\n", body(raw)) if p.strip()]

    # Drop a table of contents: "Contents" followed by heading-like lines.
    for i, p in enumerate(paras):
        if p.lower() in ("contents", "table of contents"):
            j = i + 1
            while j < len(paras) and "\n" in paras[j] and all(is_heading(l.strip()) for l in paras[j].split("\n")):
                j += 1
            del paras[i:j]
            break

    out = []
    for p in paras:
        text = re.sub(r"\s*\n\s*", " ", p)
        out.append("# " + text if is_heading(p) else text)
    return "\n\n".join(out) + "\n"


def meta(raw):
    """title=/author= lines from the Gutenberg header."""
    out = []
    for key, field in (("title", "Title"), ("author", "Author")):
        m = re.search(rf"^{field}: (.+)$", raw, re.M)
        if m:
            out.append(f"{key}={m.group(1).strip()}")
    return "\n".join(out) + "\n"


def cover(book_id, dest):
    """The full-size cover from the EPUB; Gutenberg's direct cover links are only 200 px."""
    url = f"https://www.gutenberg.org/ebooks/{book_id}.epub3.images"
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            z = zipfile.ZipFile(io.BytesIO(r.read()))
        name = next(n for n in z.namelist() if re.search(r"cover\.(jpe?g|png)$", n, re.I))
    except (StopIteration, OSError, zipfile.BadZipFile):
        return False
    tmp = dest.with_suffix(".orig")
    tmp.write_bytes(z.read(name))
    subprocess.run(["sips", "-s", "format", "jpeg", "-Z", "900", str(tmp), "--out", str(dest)],
                   check=True, capture_output=True)
    tmp.unlink()
    return True


if __name__ == "__main__":
    book_id, name = sys.argv[1], sys.argv[2]
    dest = Path(__file__).parent / "books" / f"{name}.txt"
    raw = fetch(book_id)
    dest.write_text(convert(raw), encoding="utf-8")
    dest.with_suffix(".meta").write_text(meta(raw), encoding="utf-8")
    # Gutenberg's auto-generated covers are plain; the app draws a nicer one when none is given.
    if "--cover" in sys.argv and cover(book_id, dest.with_suffix(".jpg")):
        print("cover saved")
    print(dest, f"{len(dest.read_text().split())} words")
