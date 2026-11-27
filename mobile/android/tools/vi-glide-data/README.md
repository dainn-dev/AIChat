# vi-glide-data

Builds `vi_glide_ngrams.tsv.gz`, the syllable unigram/bigram frequency asset the
keyboard uses to choose Vietnamese diacritics for glide typing. Stdlib-only
Python (3.9+).

## 1. Download the corpus

Vietnamese Wikipedia articles dump (about 1.2 GB compressed, about 1.3M pages):

    https://dumps.wikimedia.org/viwiki/latest/viwiki-latest-pages-articles.xml.bz2

Keep it outside the repo (it is not committed).

## 2. Build

From `mobile/android`:

    python tools/vi-glide-data/build_vi_glide_data.py \
      --input /path/to/viwiki-latest-pages-articles.xml.bz2 \
      --output tools/vi-glide-data/vi_glide_ngrams.tsv.gz \
      --progress

Options (defaults): `--min-unigram 5`, `--min-bigram 3`, `--top-per-prev 40`,
`--max-bytes 3000000`, `--progress` (page count to stderr every 50k pages).

Counting is two passes over the input (so the dump is read twice): pass 1
counts unigrams only (bounded by the syllable alphabet); pass 2 counts only
bigrams whose two syllables both reached `--min-unigram`, so non-Vietnamese
tokens do not bloat memory. The dump is scanned page by page with a plain
streaming reader, not an XML parser: `ElementTree.iterparse` (expat) aborted
with "out of memory" on the real dump near line 36M while the process was tiny.

Measured on the real dump (viwiki 2026-11-01, 1,165,020,800 bytes) with the
default thresholds, on a 32 GB Windows machine with Python 3.14: about 97
minutes wall time for both passes (5,821 s); Python's working set was about
40-60 MB during pass 1 and was not sampled in pass 2 (the kept-bigram table is
about 2M entries, a few hundred MB at most); output 2,855,755 bytes
(458,041 unigrams, 2,026,821 bigrams kept; counts cache 10.4 MB).

## Shipped asset

`app/src/main/assets/ime/dict/vi_glide.tsv.gz` was built from the 2026-11-01
dump (`viwiki-latest-pages-articles.xml.bz2`) with, from `mobile/android`:

    python tools/vi-glide-data/build_vi_glide_data.py       --input viwiki-latest-pages-articles.xml.bz2       --output app/src/main/assets/ime/dict/vi_glide.tsv.gz       --counts-cache counts.tsv.gz --progress

The attribution shows in the app under About > Open-source licenses
(`app/src/main/config/libraries/vi-wikipedia-statistics.json`,
`app/src/main/config/licenses/cc-by-sa-4.0-wikipedia.json`).

Use `--counts-cache counts.tsv.gz` to save the pruned counts. Re-running with
the same inputs and equal or higher `--min-unigram` / `--min-bigram` reuses the
cache and skips counting, so tuning thresholds after a budget failure is
instant. Without a cache, every retry recounts the whole corpus. A cache built
with higher thresholds than requested, or for different inputs (name/size), is
ignored and rebuilt.

If the output exceeds `--max-bytes` the tool exits non-zero, tells you which
thresholds to raise (`--min-unigram`, `--min-bigram`, or lower
`--top-per-prev`), and leaves any existing output file untouched (output is
written to a temp file and renamed on success). Bigrams are only emitted when
both syllables are kept U lines. The output is deterministic (fixed gzip
mtime), so diffs stay stable.

## Output format

UTF-8 gzip'd TSV, syllables NFC and lowercased:

    U<TAB><syllable><TAB><count>            sorted by count descending
    B<TAB><prev><TAB><syllable><TAB><count> grouped by prev, count descending

Only article pages (namespace 0, non-redirect) are read. Bigrams never cross
punctuation, digits, newlines or non-Vietnamese words. Tokens made of Vietnamese
letters but not valid syllables (e.g. "iphone") are kept here; the app's
`ViSyllable.isValid` filters them.

## Licence

Wikipedia text is CC BY-SA 4.0. The derived counts must be attributed: credit
"Vietnamese Wikipedia contributors, CC BY-SA 4.0
(https://creativecommons.org/licenses/by-sa/4.0/)" in the app's open-source
licences / about screen.

## Adding a second corpus

Pass more `--input` files; counts are summed before pruning. Accepts
`*.xml.bz2` / `*.xml` MediaWiki dumps and plain UTF-8 `*.txt` (one line is one
run boundary):

    python tools/vi-glide-data/build_vi_glide_data.py \
      --input viwiki.xml.bz2 chat-corpus.txt --output out.tsv.gz

Check the licence of any extra corpus and attribute it too.

## Tests

From the repo root:

    python -m unittest discover mobile/android/tools/vi-glide-data
