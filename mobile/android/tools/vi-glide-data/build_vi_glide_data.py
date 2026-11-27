#!/usr/bin/env python3
"""Build the Vietnamese glide-typing frequency asset from text corpora.

Turns Vietnamese Wikipedia dumps (*.xml.bz2) and/or plain *.txt files into a
small gzip'd TSV of syllable unigram and bigram counts:

    U<TAB>syllable<TAB>count
    B<TAB>prev<TAB>syllable<TAB>count

Stdlib only. Pages are stream-parsed so memory stays bounded.
"""
import argparse
import bz2
import gzip
import html
import os
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict

MAX_SYLLABLE_LETTERS = 7
PROGRESS_EVERY_PAGES = 50_000

# --- Vietnamese alphabet -----------------------------------------------------

_CONSONANTS = "bcdghklmnpqrstvx" + "đ"
_VOWELS = "aăâeêioôơuưy"
# grave, hook above, tilde, acute, dot below
_TONE_MARKS = ("̀", "̉", "̃", "́", "̣")
_LETTERS = _CONSONANTS + _VOWELS + "".join(
    unicodedata.normalize("NFC", v + t) for v in _VOWELS for t in _TONE_MARKS
)
_VI_WORD_RE = re.compile(f"[{_LETTERS}]{{1,{MAX_SYLLABLE_LETTERS}}}")

# One pass over the text: a run of letters, a run of non-newline spaces, or any
# other single character (which breaks the run).
_SCAN_RE = re.compile(r"(?P<w>[^\W\d_]+)|(?P<s>[^\S\n]+)|[\s\S]")

# --- Markup stripping --------------------------------------------------------

_REF_RE = re.compile(r"<ref\b[^>]*?/>|<ref\b[^>]*>.*?</ref\s*>", re.S | re.I)
_COMMENT_RE = re.compile(r"<!--.*?-->", re.S)
_TEMPLATE_RE = re.compile(r"\{\{[^{}]*\}\}")
_TABLE_RE = re.compile(r"\{\|(?:(?!\{\|).)*?\|\}", re.S)
_FILE_RE = re.compile(
    r"\[\[\s*(?:file|image|tập tin|tệp|hình|thể loại|category)\s*:"
    r"(?:[^\[\]]|\[\[[^\[\]]*\]\])*\]\]",
    re.I,
)
_LINK_RE = re.compile(r"\[\[(?:[^\[\]|]*\|)?([^\[\]]*)\]\]")
_TAG_RE = re.compile(r"<[^>]+>")
_URL_RE = re.compile(r"(?:https?:)?//\S+|www\.\S+", re.I)
_QUOTES_RE = re.compile(r"'{2,}")
_REDIRECT_RE = re.compile(r"\s*#(?:redirect|đổi)", re.I)
_MAX_NESTING_PASSES = 10


def _sub_until_stable(pattern, text):
    for _ in range(_MAX_NESTING_PASSES):
        new = pattern.sub("", text)
        if new == text:
            break
        text = new
    return text


def strip_wiki_markup(text: str) -> str:
    text = _COMMENT_RE.sub("", text)
    text = _REF_RE.sub("", text)
    text = _sub_until_stable(_TEMPLATE_RE, text)
    text = _sub_until_stable(_TABLE_RE, text)
    text = _FILE_RE.sub("", text)
    text = _LINK_RE.sub(r"\1", text)
    text = _TAG_RE.sub(" ", text)
    text = _URL_RE.sub(" ", text)
    text = _QUOTES_RE.sub("", text)
    return html.unescape(text)


# --- Tokenising and counting ---------------------------------------------------

def tokenize(text: str) -> list:
    """Return runs of Vietnamese syllables (lowercased NFC).

    A run breaks at punctuation, digits, newlines and at any word that is not
    a valid 1-7 letter Vietnamese-alphabet token.
    """
    text = unicodedata.normalize("NFC", text.lower())
    runs = []
    run = []
    for m in _SCAN_RE.finditer(text):
        kind = m.lastgroup
        if kind == "s":
            continue
        if kind == "w":
            word = m.group()
            if _VI_WORD_RE.fullmatch(word):
                run.append(word)
                continue
        if run:
            runs.append(run)
            run = []
    if run:
        runs.append(run)
    return runs


def count_into(unigrams: Counter, bigrams: Counter, runs) -> None:
    for run in runs:
        unigrams.update(run)
        if len(run) > 1:
            bigrams.update(zip(run, run[1:]))


def count(runs):
    unigrams, bigrams = Counter(), Counter()
    count_into(unigrams, bigrams, runs)
    return unigrams, bigrams


# --- Asset output ----------------------------------------------------------------

def write_asset(unigrams, bigrams, path, min_unigram, min_bigram,
                top_per_prev, max_bytes) -> int:
    lines = []
    kept_uni = sorted(
        ((s, c) for s, c in unigrams.items() if c >= min_unigram),
        key=lambda sc: (-sc[1], sc[0]),
    )
    lines.extend(f"U\t{s}\t{c}" for s, c in kept_uni)

    by_prev = defaultdict(list)
    for (prev, syl), c in bigrams.items():
        if c >= min_bigram:
            by_prev[prev].append((syl, c))
    groups = []
    for prev, items in by_prev.items():
        items.sort(key=lambda sc: (-sc[1], sc[0]))
        top = items[:top_per_prev]
        groups.append((prev, sum(c for _, c in top), top))
    groups.sort(key=lambda g: (-g[1], g[0]))
    for prev, _, top in groups:
        lines.extend(f"B\t{prev}\t{s}\t{c}" for s, c in top)

    payload = ("\n".join(lines) + "\n" if lines else "").encode("utf-8")
    with open(path, "wb") as raw:
        # mtime=0 and empty filename keep the output byte-for-byte reproducible.
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            gz.write(payload)
    size = os.path.getsize(path)
    if size > max_bytes:
        os.remove(path)
        raise ValueError(
            f"asset is {size} bytes, over the {max_bytes} byte budget; raise "
            f"--min-unigram, --min-bigram or lower --top-per-prev (or raise --max-bytes)"
        )
    return size


# --- Input readers ---------------------------------------------------------------

def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def iter_dump_texts(path, progress=False):
    """Yield wikitext of article (ns 0, non-redirect) pages of a MediaWiki dump."""
    opener = bz2.open if path.endswith(".bz2") else open
    pages = 0
    with opener(path, "rb") as f:
        root = None
        for event, elem in ET.iterparse(f, events=("start", "end")):
            if event == "start":
                if root is None:
                    root = elem
                continue
            if _local(elem.tag) != "page":
                continue
            pages += 1
            if progress and pages % PROGRESS_EVERY_PAGES == 0:
                print(f"{pages} pages", file=sys.stderr, flush=True)
            ns = None
            redirect = False
            for child in elem:
                name = _local(child.tag)
                if name == "ns":
                    ns = (child.text or "").strip()
                elif name == "redirect":
                    redirect = True
            text = None
            if ns == "0" and not redirect:
                for node in elem.iter():
                    if _local(node.tag) == "text":
                        text = node.text
                        break
            elem.clear()
            root.clear()  # drop finished pages kept alive by the root
            if text and not _REDIRECT_RE.match(text):
                yield text


def iter_txt_texts(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            yield line


def iter_input_texts(path, progress=False):
    lower = path.lower()
    if lower.endswith((".xml.bz2", ".xml")):
        return iter_dump_texts(path, progress)
    if lower.endswith(".txt"):
        return iter_txt_texts(path)
    raise ValueError(f"unsupported input (expected *.xml.bz2, *.xml or *.txt): {path}")


# --- CLI -----------------------------------------------------------------------

def build_parser():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--input", nargs="+", action="extend", required=True,
                   help="*.xml.bz2 MediaWiki dump(s) or *.txt file(s); counts are summed")
    p.add_argument("--output", required=True, help="output path (*.tsv.gz)")
    p.add_argument("--min-unigram", type=int, default=5)
    p.add_argument("--min-bigram", type=int, default=3)
    p.add_argument("--top-per-prev", type=int, default=40)
    p.add_argument("--max-bytes", type=int, default=3_000_000)
    p.add_argument("--progress", action="store_true",
                   help="print a page count every 50k dump pages to stderr")
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    unigrams, bigrams = Counter(), Counter()
    for path in args.input:
        for text in iter_input_texts(path, args.progress):
            count_into(unigrams, bigrams, tokenize(strip_wiki_markup(text)))
    try:
        size = write_asset(unigrams, bigrams, args.output, args.min_unigram,
                           args.min_bigram, args.top_per_prev, args.max_bytes)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    print(f"wrote {args.output}: {size} bytes "
          f"({len(unigrams)} unigrams, {len(bigrams)} bigrams seen)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
