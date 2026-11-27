import bz2
import gzip
import os
import tempfile
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
    with gzip.open(path, "rt", encoding="utf-8") as f:
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


class WriteAssetTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = os.path.join(self.dir.name, "out.tsv.gz")

    def test_write_asset_format_and_pruning(self):
        uni = Counter({"là": 10, "và": 7, "hiếm": 2})
        bi = Counter(
            {("là", "và"): 5, ("là", "x"): 4, ("là", "y"): 3, ("và", "là"): 2, ("và", "z"): 9}
        )
        size = b.write_asset(uni, bi, self.path, 5, 3, 2, 10_000)
        self.assertEqual(size, os.path.getsize(self.path))
        lines = _read_asset(self.path)
        self.assertEqual(lines[:2], ["U\tlà\t10", "U\tvà\t7"])
        self.assertNotIn("U\thiếm\t2", lines)
        bigram_lines = [l for l in lines if l.startswith("B")]
        # ("và","là") below min; top 2 per prev: là -> và,x ; và -> z
        self.assertEqual(
            sorted(bigram_lines),
            sorted(["B\tlà\tvà\t5", "B\tlà\tx\t4", "B\tvà\tz\t9"]),
        )
        # bigrams grouped by prev, by count desc within group
        self.assertLess(lines.index("B\tlà\tvà\t5"), lines.index("B\tlà\tx\t4"))
        # all U lines precede all B lines
        self.assertGreater(lines.index(bigram_lines[0]), lines.index("U\tvà\t7"))

    def test_write_asset_deterministic(self):
        uni = Counter({"a": 9, "b": 9})
        bi = Counter({("a", "b"): 4})
        b.write_asset(uni, bi, self.path, 1, 1, 5, 10_000)
        first = _read_bytes(self.path)
        b.write_asset(uni, bi, self.path, 1, 1, 5, 10_000)
        self.assertEqual(first, _read_bytes(self.path))

    def test_write_asset_rejects_over_budget(self):
        uni = Counter({f"s{i}": 100 for i in range(2000)})
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


if __name__ == "__main__":
    unittest.main()
