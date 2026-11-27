# vi-glide-data

Builds `vi_glide.tsv`, the syllable unigram/bigram frequency asset the keyboard
uses to choose Vietnamese diacritics for glide typing. Stdlib-only Python
(3.9+).

## 1. Download the corpus

Vietnamese Wikipedia articles dump (about 1.2 GB compressed, about 2.3M pages
including non-article namespaces):

    https://dumps.wikimedia.org/viwiki/latest/viwiki-latest-pages-articles.xml.bz2

Keep it outside the repo (it is not committed).

## 2. Build

From `mobile/android` (this is the exact command that produced the shipped
asset, from the 2026-11-01 dump; the dump and counts cache live outside the repo):

    python tools/vi-glide-data/build_vi_glide_data.py --input /path/to/viwiki-latest-pages-articles.xml.bz2 --output app/src/main/assets/ime/dict/vi_glide.tsv --counts-cache /path/to/counts.tsv.gz --progress

Options (defaults): `--min-unigram 5`, `--min-bigram 3`, `--top-per-prev 40`,
`--max-bytes 3000000`, `--progress` (page count to stderr every 50k pages).

The output format follows the extension: `*.tsv` is plain text (what the app
ships: AAPT deflates assets inside the APK itself, and a `.gz` asset would be
stored under a stripped name and could not be opened as gzip), `*.tsv.gz` is a
deterministic gzip. `--max-bytes` is a budget on `zlib.compress(tsv, 9)`, i.e.
what the APK carries, whatever the output format.

Only syllable-shaped entries are written: `is_vi_syllable` (a port of the app's
`ViSyllable.kt`, itself ported from Bamboo, MIT) must accept a U line, and a B
line needs both syllables accepted. English words, abbreviations and letters
that appear in Vietnamese text (`and`, `km`, `s`, ...) are dropped.

Counting is two passes over the input (so the dump is read twice): pass 1
counts unigrams only (bounded by the syllable alphabet); pass 2 counts only
bigrams whose two syllables both reached `--min-unigram`, so non-Vietnamese
tokens do not bloat memory. The dump is scanned page by page with a plain
streaming reader, not an XML parser: `ElementTree.iterparse` (expat) aborted
with "out of memory" on the real dump near line 36M while the process was tiny.
Entities are unescaped; CDATA sections are not handled (the Wikipedia export
never uses them).

Measured on the real dump (viwiki 2026-11-01, 1,165,020,800 bytes) with the
default thresholds, on a 32 GB Windows machine with Python 3.14:

- wall time: 5,821 s (about 97 minutes) for both counting passes; regenerating
  from the counts cache takes about 5 s;
- peak memory: about 1 GB working set (Task Manager, observed during pass 2);
- counts cache: 10.4 MB, 458,041 unigrams and 2,026,821 bigrams before filtering;
- shipped asset `app/src/main/assets/ime/dict/vi_glide.tsv`: 2,802,736 bytes
  plain, 855,270 bytes deflated, 8,984 `U` lines and 160,805 `B` lines (counts
  after the syllable filter and pruning).

The attribution shows in the app under About > Open-source licenses
(`app/src/main/config/libraries/vi-wikipedia-statistics.json`,
`app/src/main/config/licenses/cc-by-sa-4.0-wikipedia.json`).

Use `--counts-cache counts.tsv.gz` to save the pruned counts. Re-running with
the same inputs and equal or higher `--min-unigram` / `--min-bigram` reuses the
cache and skips counting, so tuning thresholds after a budget failure is
instant. Without a cache, every retry recounts the whole corpus. A cache built
with higher thresholds than requested, or for different inputs (name/size), is
ignored and rebuilt.

If the deflated size exceeds `--max-bytes` the tool exits non-zero, tells you
which thresholds to raise (`--min-unigram`, `--min-bigram`, or lower
`--top-per-prev`), and leaves any existing output file untouched (output is
written to a temp file and renamed on success). Bigrams are only emitted when
both syllables are kept U lines. The output is deterministic, so diffs stay
stable.

## Output format

UTF-8 TSV (plain, or gzip'd for `*.tsv.gz`), syllables NFC and lowercased:

    U<TAB><syllable><TAB><count>            sorted by count descending
    B<TAB><prev><TAB><syllable><TAB><count> grouped by prev, count descending

Only article pages (namespace 0, non-redirect) are read. Bigrams never cross
punctuation, digits, newlines or non-Vietnamese words. Counts are measured on
all syllable-like tokens, but only valid syllables are written.

## Licence

Wikipedia text is CC BY-SA 4.0. The derived counts must be attributed: credit
"Vietnamese Wikipedia contributors, CC BY-SA 4.0
(https://creativecommons.org/licenses/by-sa/4.0/)" in the app's open-source
licences / about screen.

## Adding a second corpus

Pass more `--input` files; counts are summed before pruning. Accepts
`*.xml.bz2` / `*.xml` MediaWiki dumps and plain UTF-8 `*.txt` (one line is one
run boundary):

    python tools/vi-glide-data/build_vi_glide_data.py --input viwiki.xml.bz2 chat-corpus.txt --output out.tsv

Check the licence of any extra corpus and attribute it too.

## Tests

From `mobile/android`:

    python -m unittest discover tools/vi-glide-data
