import bz2
import gzip
import os
import tempfile
import unicodedata
import unittest
from collections import Counter

import build_vi_glide_data as b

NS = "http://www.mediawiki.org/xml/export-0.11/"


def _page(title, ns, text, redirect=False):
    red = f'<redirect title="X" />' if redirect else ""
    return (
        f"<page><title>{title}</title><ns>{ns}</ns><id>1</id>{red}"
        f'<revision><id>2</id><text xml:space="preserve">{text}</text></revision></page>'
    )


def _dump_bytes(*pages):
    xml = f'<mediawiki xmlns="{NS}" version="0.11"><siteinfo><sitename>x</sitename></siteinfo>'
    xml += "".join(pages) + "</mediawiki>"
    return bz2.compress(xml.encode("utf-8"))


def _read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def _read_asset(path):
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            return f.read().splitlines()
    with open(path, "r", encoding="utf-8") as f:
        return f.read().splitlines()


class StripMarkupTest(unittest.TestCase):
    def test_strip_markup(self):
        out = b.strip_wiki_markup("Hà [[Nội|Hà Nội]] {{cite}}<ref>x</ref> là")
        self.assertEqual(" ".join(out.split()), "Hà Hà Nội là")

    def test_strip_files_tables_urls_nested(self):
        text = (
            "a [[File:x.jpg|thumb|cap [[link]] here]] b {{t|{{u}}}} c "
            "{| class=x\n|- foo\n|}\n d http://e.com/x?y=1 e <br/> f"
        )
        out = " ".join(b.strip_wiki_markup(text).split())
        self.assertEqual(out, "a b c d e f")


class TokenizeTest(unittest.TestCase):
    def test_tokenize_breaks_runs_at_punctuation_and_digits(self):
        self.assertEqual(
            b.tokenize("Không được, 2 người ta."),
            [["không", "được"], ["người", "ta"]],
        )

    def test_tokenize_drops_non_vietnamese(self):
        self.assertEqual(b.tokenize("Wifi xịn"), [["xịn"]])
        self.assertEqual(b.tokenize("a Wifi b"), [["a"], ["b"]])
        self.assertEqual(b.tokenize("iphone"), [["iphone"]])

    def test_tokenize_newline_breaks_and_length_limit(self):
        self.assertEqual(b.tokenize("ta\nnhà"), [["ta"], ["nhà"]])
        self.assertEqual(b.tokenize("nghiêng abcdefgh"), [["nghiêng"]])

    def test_tokenize_normalises_nfc_and_case(self):
        decomposed = "Việt".replace("ệ", "ệ")
        self.assertEqual(b.tokenize(decomposed.upper() + " Đẹp"), [["việt", "đẹp"]])


class CountTest(unittest.TestCase):
    def test_count_bigrams_within_runs_only(self):
        uni, bi = b.count([["a", "b", "c"], ["d", "e"]])
        self.assertEqual(uni, Counter({"a": 1, "b": 1, "c": 1, "d": 1, "e": 1}))
        self.assertEqual(
            bi, Counter({("a", "b"): 1, ("b", "c"): 1, ("d", "e"): 1})
        )
        self.assertNotIn(("c", "d"), bi)


class IsViSyllableTest(unittest.TestCase):
    ACCEPT = ("được người không nghiêng quý già giữa thuyền khuỷu ở ạ nhé và là của một trong "
              "năm các có với đã gì giờ quá nghĩ tiếng việt nam hoà thuỷ khoẻ tôi").split()
    REJECT = "and text till album km s m c xyz iphone bar wifi".split()

    def test_accepts_common_syllables(self):
        self.assertEqual([w for w in self.ACCEPT if not b.is_vi_syllable(w)], [])

    def test_rejects_non_syllables(self):
        self.assertEqual([w for w in self.REJECT if b.is_vi_syllable(w)], [])

    def test_stop_final_takes_only_acute_or_dot(self):
        self.assertTrue(b.is_vi_syllable("khác"))
        self.assertTrue(b.is_vi_syllable("học"))
        self.assertFalse(b.is_vi_syllable("khàc"))

    def test_two_tones_and_empty_rejected(self):
        self.assertFalse(b.is_vi_syllable("ngườí"))
        self.assertFalse(b.is_vi_syllable(""))

    def test_is_case_and_normalisation_insensitive(self):
        self.assertTrue(b.is_vi_syllable("NGƯỜI"))
        self.assertTrue(b.is_vi_syllable(unicodedata.normalize("NFD", "người")))


class WriteAssetTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = os.path.join(self.dir.name, "out.tsv")

    def test_write_asset_format_and_pruning(self):
        uni = Counter({"là": 10, "và": 7, "hiếm": 2, "có": 6, "của": 6, "một": 6})
        bi = Counter(
            {("là", "và"): 5, ("là", "có"): 4, ("là", "của"): 3, ("và", "là"): 2, ("và", "một"): 9}
        )
        result = b.write_asset(uni, bi, self.path, 5, 3, 2, 10_000)
        self.assertEqual(result.size, os.path.getsize(self.path))
        lines = _read_asset(self.path)
        self.assertEqual(lines[:2], ["U\tlà\t10", "U\tvà\t7"])
        self.assertNotIn("U\thiếm\t2", lines)
        bigram_lines = [l for l in lines if l.startswith("B")]
        # ("và","là") below min; top 2 per prev: là -> và,có ; và -> một
        self.assertEqual(
            sorted(bigram_lines),
            sorted(["B\tlà\tvà\t5", "B\tlà\tcó\t4", "B\tvà\tmột\t9"]),
        )
        self.assertLess(lines.index("B\tlà\tvà\t5"), lines.index("B\tlà\tcó\t4"))
        self.assertGreater(lines.index(bigram_lines[0]), lines.index("U\tvà\t7"))
        self.assertEqual((result.unigrams, result.bigrams), (5, 3))

    def test_counts_are_post_prune(self):
        uni = Counter({"là": 10, "and": 50, "text": 40})
        bi = Counter({("là", "and"): 9, ("and", "là"): 9, ("là", "là"): 4})
        result = b.write_asset(uni, bi, self.path, 1, 1, 5, 10_000)
        self.assertEqual((result.unigrams, result.bigrams), (1, 1))
        lines = _read_asset(self.path)
        self.assertEqual(lines, ["U\tlà\t10", "B\tlà\tlà\t4"])

    def test_invalid_syllables_dropped_from_u_and_b(self):
        uni = Counter({"là": 9, "bar": 99, "km": 99, "s": 99, "và": 8})
        bi = Counter({("là", "bar"): 5, ("km", "là"): 5, ("là", "và"): 5})
        b.write_asset(uni, bi, self.path, 1, 1, 5, 10_000)
        lines = _read_asset(self.path)
        self.assertEqual(
            lines, ["U\tlà\t9", "U\tvà\t8", "B\tlà\tvà\t5"])

    def test_plain_output_for_tsv_and_gzip_for_gz(self):
        uni = Counter({"là": 9})
        b.write_asset(uni, Counter(), self.path, 1, 1, 5, 10_000)
        self.assertEqual(_read_bytes(self.path), "U\tlà\t9\n".encode("utf-8"))
        gz = os.path.join(self.dir.name, "out.tsv.gz")
        b.write_asset(uni, Counter(), gz, 1, 1, 5, 10_000)
        self.assertEqual(_read_bytes(gz)[:2], b"\x1f\x8b")
        self.assertEqual(_read_asset(gz), ["U\tlà\t9"])

    def test_budget_measures_deflate_not_output_format(self):
        syllables = [a + v for a in ("b", "c", "d", "g", "h", "k", "l", "m", "n") for v in ("a", "e", "i", "o", "u")]
        uni = Counter({s: 1000 + i for i, s in enumerate(syllables)})
        res = b.write_asset(uni, Counter(), self.path, 1, 1, 5, 10_000)
        self.assertLess(res.deflate_size, res.size)  # plain TSV is bigger than its deflate
        # a budget between deflate and plain size passes for plain output...
        b.write_asset(uni, Counter(), self.path, 1, 1, 5, res.deflate_size)
        # ...and one byte less fails, whatever the output format
        for name in ("o.tsv", "o.tsv.gz"):
            with self.assertRaises(ValueError):
                b.write_asset(uni, Counter(), os.path.join(self.dir.name, name), 1, 1, 5,
                              res.deflate_size - 1)

    def test_over_budget_leaves_existing_output_intact(self):
        with open(self.path, "wb") as f:
            f.write(b"previous-good-asset")
        uni = Counter({a + v: 100 for a in "bcdghklmn" for v in "aeiou"})
        with self.assertRaises(ValueError):
            b.write_asset(uni, Counter(), self.path, 1, 1, 5, 50)
        self.assertEqual(_read_bytes(self.path), b"previous-good-asset")
        self.assertFalse(os.path.exists(self.path + ".tmp"))

    def test_write_asset_deterministic(self):
        uni = Counter({"là": 9, "và": 9})
        bi = Counter({("là", "và"): 4})
        for name in ("a.tsv", "a.tsv.gz"):
            path = os.path.join(self.dir.name, name)
            b.write_asset(uni, bi, path, 1, 1, 5, 10_000)
            first = _read_bytes(path)
            b.write_asset(uni, bi, path, 1, 1, 5, 10_000)
            self.assertEqual(first, _read_bytes(path))

    def test_write_asset_rejects_over_budget(self):
        uni = Counter({a + v: 100 for a in "bcdghklmn" for v in "aeiou"})
        with self.assertRaises(ValueError) as ctx:
            b.write_asset(uni, Counter(), self.path, 1, 1, 5, 50)
        msg = str(ctx.exception)
        self.assertIn("--min-unigram", msg)
        self.assertIn("--min-bigram", msg)
        self.assertIn("--top-per-prev", msg)


class DumpParsingTest(unittest.TestCase):
    def test_dump_parsing(self):
        data = _dump_bytes(
            _page("A", 0, "Hà [[Nội|Hà Nội]] là"),
            _page("Talk", 1, "bỏ qua"),
            _page("R", 0, "#REDIRECT [[A]]", redirect=True),
            _page("B", 0, "người ta"),
        )
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.xml.bz2")
            with open(p, "wb") as f:
                f.write(data)
            texts = list(b.iter_dump_texts(p))
        self.assertEqual(texts, ["Hà [[Nội|Hà Nội]] là", "người ta"])

    def _texts(self, xml, **kw):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.xml")
            with open(p, "wb") as f:
                f.write(xml.encode("utf-8"))
            return list(b.iter_dump_texts(p, **kw))

    def test_entities_are_unescaped_once(self):
        page = _page("A", 0, "a &amp;amp; b &lt;ref&gt; &quot;x&quot; &#7897;")
        self.assertEqual(self._texts(page), ["a &amp; b <ref> \"x\" \u1ed9"])

    def test_text_tag_with_attributes_and_self_closing(self):
        attrs = ("<page><title>A</title><ns>0</ns><id>1</id><revision>"
                 '<text xml:space="preserve" bytes="12">tôi là</text></revision></page>')
        empty = ('<page><title>E</title><ns>0</ns><id>2</id><revision>'
                 '<text bytes="0" xml:space="preserve" /></revision></page>')
        self.assertEqual(self._texts(attrs + empty + attrs), ["tôi là", "tôi là"])

    def test_redirect_and_nonzero_namespace_skipped(self):
        xml = (_page("R", 0, "#REDIRECT [[A]]", redirect=True) + _page("T", 1, "bỏ qua")
               + _page("U", 2, "bỏ qua") + _page("A", 0, "giữ lại")
               + _page("R2", 0, "#đổi [[A]]"))
        self.assertEqual(self._texts(xml), ["giữ lại"])

    def test_pages_spanning_read_boundaries(self):
        pages = [_page(f"P{i}", 0, f"tôi là người số {i} " + "và " * (i % 7)) for i in range(40)]
        expected = [f"tôi là người số {i} " + "và " * (i % 7) for i in range(40)]
        whole = self._texts("".join(pages))
        self.assertEqual(whole, expected)
        for size in (1, 3, 7, 50, 333):  # multi-byte chars also split mid-sequence
            self.assertEqual(self._texts("".join(pages), read_bytes=size), expected)

    def test_main_sums_multiple_inputs_and_is_deterministic(self):
        with tempfile.TemporaryDirectory() as d:
            dump = os.path.join(d, "x.xml.bz2")
            with open(dump, "wb") as f:
                f.write(_dump_bytes(_page("A", 0, "tôi là người. tôi là")))
            txt = os.path.join(d, "y.txt")
            with open(txt, "w", encoding="utf-8") as f:
                f.write("tôi là\nkhác\n")
            out1 = os.path.join(d, "o1.tsv.gz")
            out2 = os.path.join(d, "o2.tsv.gz")
            for out in (out1, out2):
                rc = b.main(
                    ["--input", dump, "--input", txt, "--output", out,
                     "--min-unigram", "1", "--min-bigram", "1"]
                )
                self.assertEqual(rc, 0)
            lines = _read_asset(out1)
            self.assertIn("U\ttôi\t3", lines)
            self.assertIn("U\tlà\t3", lines)
            self.assertIn("B\ttôi\tlà\t3", lines)
            self.assertEqual(_read_bytes(out1), _read_bytes(out2))


CORPUS = "tôi là người\ntôi là Wifi người\ntôi là người ta\nhiếm lạ\n"


class TwoPassAndCacheTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.txt = os.path.join(self.dir.name, "c.txt")
        with open(self.txt, "w", encoding="utf-8") as f:
            f.write(CORPUS)

    def test_two_pass_equals_one_pass_on_kept_syllables(self):
        runs = [r for line in CORPUS.splitlines() for r in b.tokenize(line)]
        uni1, bi1 = b.count(runs)
        kept = {s for s, c in uni1.items() if c >= 2}
        expected = Counter({k: c for k, c in bi1.items()
                            if k[0] in kept and k[1] in kept})
        self.assertEqual(b.count_unigrams([self.txt]), uni1)
        self.assertEqual(b.count_bigrams([self.txt], kept), expected)

    def test_cache_round_trip_gives_same_output(self):
        cache = os.path.join(self.dir.name, "counts.tsv.gz")
        out1 = os.path.join(self.dir.name, "o1.tsv.gz")
        out2 = os.path.join(self.dir.name, "o2.tsv.gz")
        base = ["--input", self.txt, "--min-unigram", "2", "--min-bigram", "2"]
        self.assertEqual(b.main(base + ["--output", out1, "--counts-cache", cache]), 0)
        # same size and name, different content: only a valid cache can match
        with open(self.txt, "w", encoding="utf-8") as f:
            f.write(CORPUS.replace("hiếm lạ", "xxxx yyyy"))
        self.assertEqual(b.main(base + ["--output", out2, "--counts-cache", cache]), 0)
        self.assertEqual(_read_bytes(out1), _read_bytes(out2))
        self.assertIn("B\ttôi\tlà\t3", _read_asset(out2))

    def test_stale_cache_is_recounted(self):
        cache = os.path.join(self.dir.name, "counts.tsv.gz")
        out = os.path.join(self.dir.name, "o.tsv.gz")
        base = ["--input", self.txt, "--min-unigram", "2", "--min-bigram", "2",
                "--output", out, "--counts-cache", cache]
        b.main(base)
        with open(self.txt, "a", encoding="utf-8") as f:
            f.write("tôi là người\n")
        b.main(base)
        self.assertIn("U\ttôi\t4", _read_asset(out))

    def test_validate_inputs_fails_before_counting(self):
        out = os.path.join(self.dir.name, "o.tsv.gz")
        self.assertEqual(b.main(["--input", self.txt, "nope.txt", "--output", out]), 1)
        bad = os.path.join(self.dir.name, "x.csv")
        open(bad, "w").close()
        self.assertEqual(b.main(["--input", bad, "--output", out]), 1)
        self.assertFalse(os.path.exists(out))


if __name__ == "__main__":
    unittest.main()
