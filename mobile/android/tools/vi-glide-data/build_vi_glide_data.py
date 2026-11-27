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

def _gzip_to_temp(path, payload: bytes) -> str:
    """Write a deterministic gzip next to `path` and return the temp path."""
    tmp = f"{path}.tmp"
    with open(tmp, "wb") as raw:
        # mtime=0 and empty filename keep the output byte-for-byte reproducible.
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            gz.write(payload)
    return tmp


def write_asset(unigrams, bigrams, path, min_unigram, min_bigram,
                top_per_prev, max_bytes) -> int:
    """Write the asset atomically; an existing file at `path` survives failures.

    Bigrams are kept only when both syllables survive `min_unigram`.
    """
    kept_uni = sorted(
        ((s, c) for s, c in unigrams.items() if c >= min_unigram),
        key=lambda sc: (-sc[1], sc[0]),
    )
    kept = {s for s, _ in kept_uni}
    lines = [f"U\t{s}\t{c}" for s, c in kept_uni]

    by_prev = defaultdict(list)
    for (prev, syl), c in bigrams.items():
        if c >= min_bigram and prev in kept and syl in kept:
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
    tmp = _gzip_to_temp(path, payload)
    size = os.path.getsize(tmp)
    if size > max_bytes:
        os.remove(tmp)
        raise ValueError(
            f"asset is {size} bytes, over the {max_bytes} byte budget; raise "
            f"--min-unigram, --min-bigram or lower --top-per-prev (or raise --max-bytes)"
        )
    os.replace(tmp, path)
    return size


# --- Counts cache -------------------------------------------------------------------

def save_counts_cache(path, fingerprint, min_unigram, min_bigram, unigrams, bigrams):
    lines = [
        "#fingerprint\t" + "|".join(fingerprint),
        f"#min_unigram\t{min_unigram}",
        f"#min_bigram\t{min_bigram}",
    ]
    lines.extend(f"U\t{s}\t{c}" for s, c in sorted(unigrams.items()))
    lines.extend(
        f"B\t{p}\t{s}\t{c}" for (p, s), c in sorted(bigrams.items())
        if c >= min_bigram
    )
    tmp = _gzip_to_temp(path, ("\n".join(lines) + "\n").encode("utf-8"))
    os.replace(tmp, path)


def load_counts_cache(path):
    """Return (fingerprint, min_unigram, min_bigram, unigrams, bigrams) or None."""
    try:
        with gzip.open(path, "rt", encoding="utf-8") as f:
            meta, unigrams, bigrams = {}, Counter(), Counter()
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if parts[0].startswith("#"):
                    meta[parts[0][1:]] = parts[1]
                elif parts[0] == "U":
                    unigrams[parts[1]] = int(parts[2])
                elif parts[0] == "B":
                    bigrams[(parts[1], parts[2])] = int(parts[3])
        return (meta["fingerprint"].split("|"), int(meta["min_unigram"]),
                int(meta["min_bigram"]), unigrams, bigrams)
    except (OSError, EOFError, KeyError, ValueError, IndexError):
        return None


# --- Input readers ---------------------------------------------------------------

_PAGE_END = "</page>"
_NS_RE = re.compile(r"<ns>\s*(\d+)\s*</ns>")
_REDIRECT_TAG_RE = re.compile(r"<redirect\b")
_TEXT_RE = re.compile(r"<text\b[^>]*?(?<!/)>(.*?)</text>", re.S)
_READ_CHUNK_BYTES = 4 * 1024 * 1024


def _page_text(page):
    """Wikitext of an article (ns 0, non-redirect) page body, else None."""
    ns = _NS_RE.search(page)
    if ns is None or ns.group(1) != "0" or _REDIRECT_TAG_RE.search(page):
        return None
    m = _TEXT_RE.search(page)
    return html.unescape(m.group(1)) if m else None


def iter_dump_texts(path, progress=False):
    """Yield wikitext of article (ns 0, non-redirect) pages of a MediaWiki dump.

    Streams the dump page by page with a plain scanner instead of an XML parser:
    expat (via ElementTree.iterparse) aborted with "out of memory" on the real
    viwiki dump at a page near line 36M, with the process far below any limit.
    """
    import codecs
    opener = bz2.open if path.endswith(".bz2") else open
    decoder = codecs.getincrementaldecoder("utf-8")("replace")
    pages = 0
    buf = ""
    with opener(path, "rb") as f:
        while True:
            chunk = f.read(_READ_CHUNK_BYTES)
            buf += decoder.decode(chunk, final=not chunk)
            while True:
                end = buf.find(_PAGE_END)
                if end < 0:
                    break
                start = buf.rfind("<page>", 0, end)
                page = buf[start:end] if start >= 0 else ""
                buf = buf[end + len(_PAGE_END):]
                pages += 1
                if progress and pages % PROGRESS_EVERY_PAGES == 0:
                    print(f"{pages} pages", file=sys.stderr, flush=True)
                text = _page_text(page)
                if text and not _REDIRECT_RE.match(text):
                    yield text
            if not chunk:
                return


def iter_txt_texts(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            yield line


SUPPORTED_EXTENSIONS = (".xml.bz2", ".xml", ".txt")


def validate_inputs(paths) -> None:
    """Fail fast, before any counting, on missing or unsupported inputs."""
    for path in paths:
        if not os.path.isfile(path):
            raise ValueError(f"input not found: {path}")
        if not path.lower().endswith(SUPPORTED_EXTENSIONS):
            raise ValueError(
                f"unsupported input (expected *.xml.bz2, *.xml or *.txt): {path}")


def iter_input_texts(path, progress=False):
    validate_inputs([path])
    if path.lower().endswith(".txt"):
        return iter_txt_texts(path)
    return iter_dump_texts(path, progress)


def iter_runs(paths, progress=False):
    for path in paths:
        for text in iter_input_texts(path, progress):
            yield from tokenize(strip_wiki_markup(text))


def count_unigrams(paths, progress=False) -> Counter:
    """Pass 1: unigrams only. Bounded by the syllable alphabet, not corpus size."""
    unigrams = Counter()
    for run in iter_runs(paths, progress):
        unigrams.update(run)
    return unigrams


def count_bigrams(paths, kept, progress=False) -> Counter:
    """Pass 2: bigrams whose two syllables are both in `kept`."""
    bigrams = Counter()
    for run in iter_runs(paths, progress):
        if len(run) > 1:
            bigrams.update(
                pair for pair in zip(run, run[1:])
                if pair[0] in kept and pair[1] in kept
            )
    return bigrams


def fingerprint_inputs(paths):
    return [f"{os.path.basename(p)}:{os.path.getsize(p)}" for p in paths]


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
    p.add_argument("--counts-cache", metavar="PATH",
                   help="save/reuse pruned counts (gzip TSV) so retrying thresholds "
                        "does not recount the corpus")
    p.add_argument("--progress", action="store_true",
                   help="print a page count every 50k dump pages to stderr")
    return p


def _counts(args):
    """Return (unigrams, bigrams), from the cache when it is valid for these args."""
    fingerprint = fingerprint_inputs(args.input)
    if args.counts_cache and os.path.exists(args.counts_cache):
        cached = load_counts_cache(args.counts_cache)
        if (cached and cached[0] == fingerprint
                and args.min_unigram >= cached[1] and args.min_bigram >= cached[2]):
            print(f"reusing counts cache {args.counts_cache}", file=sys.stderr)
            return cached[3], cached[4]
        print("counts cache is stale or built with higher thresholds; recounting",
              file=sys.stderr)
    unigrams = count_unigrams(args.input, args.progress)
    kept = {s for s, c in unigrams.items() if c >= args.min_unigram}
    bigrams = count_bigrams(args.input, kept, args.progress)
    bigrams = Counter({k: c for k, c in bigrams.items() if c >= args.min_bigram})
    if args.counts_cache:
        save_counts_cache(args.counts_cache, fingerprint, args.min_unigram,
                          args.min_bigram, unigrams, bigrams)
    return unigrams, bigrams


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        validate_inputs(args.input)
        unigrams, bigrams = _counts(args)
        size = write_asset(unigrams, bigrams, args.output, args.min_unigram,
                           args.min_bigram, args.top_per_prev, args.max_bytes)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        if not args.counts_cache and "budget" in str(e):
            print("note: retrying thresholds recounts the whole corpus; "
                  "pass --counts-cache to avoid that", file=sys.stderr)
        return 1
    print(f"wrote {args.output}: {size} bytes "
          f"({len(unigrams)} unigrams, {len(bigrams)} bigrams kept)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
