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

Counting is two passes over the input (so the dump is read and parsed twice):
pass 1 counts unigrams only (bounded by the syllable alphabet); pass 2 counts
only bigrams whose two syllables both reached `--min-unigram`, so
non-Vietnamese tokens do not bloat memory. Estimates, not yet measured on the
real dump (Task 3 will record real figures): about 1-2 hours total, and memory
bounded by the kept-syllable bigram table (likely several hundred MB to about
1 GB). Output is about 1-3 MB.

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
