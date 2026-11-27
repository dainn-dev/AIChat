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
import codecs
import gzip
import html
import os
import re
import sys
import unicodedata
import zlib
from collections import Counter, defaultdict, namedtuple

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


# --- Vietnamese syllable shape ---------------------------------------------------
#
# Ported from the app's ViSyllable.kt (vi-input), which is itself ported from Bamboo
# (https://github.com/BambooEngine/bamboo-core, spelling.go and the CVC splitting in
# bamboo_utils.go), commit b2e49a2b48c7d3772a3673142a7747eccd9d5f79.
# Copyright (C) 2018 Luong Thanh Lam <ltlam93@gmail.com>. MIT License, see NOTICE.
# Complete syllables only (the Kotlin `complete = true` path, plus a required vowel).

_FIRST_CONSONANTS = [r.split() for r in (
    "b d đ g gh m n nh p ph r s t tr v z",
    "c h k kh qu th",
    "ch gi l ng ngh x",
    "đ l",
    "h",
)]
_VOWEL_ROWS = [r.split() for r in (
    "ê i ua uê uy y",
    "a iê oa uyê yê",
    "â ă e o oo ô ơ oe u ư uâ uô ươ",
    "oă",
    "uơ",
    "ai ao au âu ay ây eo êu ia iêu iu oai oao oay oeo oi ôi ơi ưa uây ui ưi uôi ươi ươu ưu uya uyu yêu",
    "ă",
    "i",
)]
_LAST_CONSONANTS = [r.split() for r in ("ch nh", "c ng", "m n p t", "k", "c")]
_CV_MATRIX = [{0, 1, 2, 5}, {0, 1, 2, 3, 4, 5}, {0, 1, 2, 3, 5}, {6}, {7}]
_VC_MATRIX = [{0, 2}, {0, 1, 2}, {1, 2}, {1, 2}, set(), set(), {3}, {4}]
_STOP_FINALS = {"c", "k", "p", "t", "ch"}
_ACUTE = "́"
_DOT_BELOW = "̣"
# Combining tone marks: grave, hook above, tilde, acute, dot below.
_TONE_CODEPOINTS = frozenset("̣̀̉̃́")
_PLAIN_VOWELS = frozenset("aeiouy")


def _rows_of(table, text):
    return [i for i, row in enumerate(table) if text in row]


def _tone_of(ch):
    return next((c for c in unicodedata.normalize("NFD", ch) if c in _TONE_CODEPOINTS), "")


def _strip_tone(ch):
    return unicodedata.normalize(
        "NFC", "".join(c for c in unicodedata.normalize("NFD", ch) if c not in _TONE_CODEPOINTS))


def _base_letter(ch):
    return unicodedata.normalize("NFD", ch)[0]


def _is_vowel_char(ch):
    return _base_letter(ch) in _PLAIN_VOWELS


def _run_start(letters, end, vowels):
    i = end
    while i > 0 and _is_vowel_char(letters[i - 1]) == vowels:
        i -= 1
    return i


def _split_cvc(word):
    """(initial, vowel, final) with gi/qu counted as initials (Bamboo extractCvc)."""
    last_start = _run_start(word, len(word), False)
    vowel_start = _run_start(word, last_start, True)
    fc, vo, lc = word[:vowel_start], word[vowel_start:last_start], word[last_start:]
    if lc and not vo and not fc:
        fc, lc = lc, ""
    if len(fc) == 1 and vo:
        first, v0 = _base_letter(fc[0]), _base_letter(vo[0])
        gi = (first == "g" and v0 == "i" and len(vo) > 1
              and not (_base_letter(vo[1]) == "e" and lc))
        qu = first == "q" and v0 == "u"
        if gi or qu:
            fc, vo = fc + vo[0], vo[1:]
    return fc, vo, lc


def _combines(left, right, matrix):
    return any(r in matrix[l] for l in left for r in right)


def is_vi_syllable(word: str) -> bool:
    """True if `word` is a complete, validly spelled Vietnamese syllable."""
    word = unicodedata.normalize("NFC", word.lower())
    if not word:
        return False
    tones = [t for t in map(_tone_of, word) if t]
    if len(tones) > 1:
        return False
    fc, vo, lc = _split_cvc(word)
    if not vo:
        return False
    if tones and tones[0] not in (_ACUTE, _DOT_BELOW) and lc in _STOP_FINALS:
        return False
    fc, vo = "".join(map(_strip_tone, fc)), "".join(map(_strip_tone, vo))
    fc_rows = _rows_of(_FIRST_CONSONANTS, fc) if fc else []
    if fc and not fc_rows:
        return False
    vo_rows = _rows_of(_VOWEL_ROWS, vo)
    if not vo_rows:
        return False
    lc_rows = _rows_of(_LAST_CONSONANTS, lc) if lc else []
    if lc and not lc_rows:
        return False
    if fc_rows and not _combines(fc_rows, vo_rows, _CV_MATRIX):
        return False
    return not lc_rows or _combines(vo_rows, lc_rows, _VC_MATRIX)


# --- Asset output ----------------------------------------------------------------

AssetResult = namedtuple("AssetResult", "size deflate_size unigrams bigrams")


def _write_temp(path, payload: bytes, gz=None) -> str:
    """Write the asset next to `path` and return the temp path.

    A path ending in .gz gets a deterministic gzip (mtime=0, empty filename);
    anything else gets the plain TSV, which is what the app ships (the APK
    deflates assets itself).
    """
    tmp = f"{path}.tmp"
    with open(tmp, "wb") as raw:
        if path.endswith(".gz") if gz is None else gz:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
                gz.write(payload)
        else:
            raw.write(payload)
    return tmp


def write_asset(unigrams, bigrams, path, min_unigram, min_bigram,
                top_per_prev, max_bytes) -> AssetResult:
    """Write the asset atomically; an existing file at `path` survives failures.

    Only syllable-shaped (is_vi_syllable) U lines are kept, and B lines only when
    both syllables are kept. The budget is the deflated size of the TSV, which is
    what the APK carries, whatever the output format.
    """
    kept_uni = sorted(
        ((s, c) for s, c in unigrams.items() if c >= min_unigram and is_vi_syllable(s)),
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
    bigram_lines = [f"B\t{prev}\t{s}\t{c}" for prev, _, top in groups for s, c in top]
    lines.extend(bigram_lines)

    payload = ("\n".join(lines) + "\n" if lines else "").encode("utf-8")
    deflate_size = len(zlib.compress(payload, 9))
    if deflate_size > max_bytes:
        raise ValueError(
            f"asset deflates to {deflate_size} bytes, over the {max_bytes} byte budget; raise "
            f"--min-unigram, --min-bigram or lower --top-per-prev (or raise --max-bytes)"
        )
    tmp = _write_temp(path, payload)
    size = os.path.getsize(tmp)
    os.replace(tmp, path)
    return AssetResult(size, deflate_size, len(kept_uni), len(bigram_lines))


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
    tmp = _write_temp(path, ("\n".join(lines) + "\n").encode("utf-8"), gz=True)
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


def iter_dump_texts(path, progress=False, read_bytes=_READ_CHUNK_BYTES):
    """Yield wikitext of article (ns 0, non-redirect) pages of a MediaWiki dump.

    Streams the dump page by page with a plain scanner instead of an XML parser:
    expat (via ElementTree.iterparse) aborted with "out of memory" on the real
    viwiki dump at a page near line 36M, with the process far below any limit.
    Entities are unescaped; CDATA sections are not handled (the Wikipedia export
    never uses them).
    """
    opener = bz2.open if path.endswith(".bz2") else open
    decoder = codecs.getincrementaldecoder("utf-8")("replace")
    pages = 0
    buf = ""
    with opener(path, "rb") as f:
        while True:
            chunk = f.read(read_bytes)
            buf += decoder.decode(chunk, final=not chunk)
            pos = 0
            while True:
                end = buf.find(_PAGE_END, pos)
                if end < 0:
                    break
                start = buf.rfind("<page>", pos, end)
                page = buf[start:end] if start >= 0 else ""
                pos = end + len(_PAGE_END)
                pages += 1
                if progress and pages % PROGRESS_EVERY_PAGES == 0:
                    print(f"{pages} pages", file=sys.stderr, flush=True)
                text = _page_text(page)
                if text and not _REDIRECT_RE.match(text):
                    yield text
            buf = buf[pos:]  # one copy per chunk, not per page
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
    p.add_argument("--output", required=True, help="output path: *.tsv (plain, what the app ships) or *.tsv.gz")
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
        result = write_asset(unigrams, bigrams, args.output, args.min_unigram,
                             args.min_bigram, args.top_per_prev, args.max_bytes)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        if not args.counts_cache and "budget" in str(e):
            print("note: retrying thresholds recounts the whole corpus; "
                  "pass --counts-cache to avoid that", file=sys.stderr)
        return 1
    print(f"wrote {args.output}: {result.size} bytes ({result.deflate_size} deflated); "
          f"{result.unigrams} unigram lines, {result.bigrams} bigram lines",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
