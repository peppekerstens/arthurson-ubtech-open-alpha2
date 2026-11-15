"""Tests for the S-23 translation-memory tools.

A small fake git repo is built in a temp folder (git init). The tests cover:
  * extract: a translation commit produces the expected rows
  * apply on an unchanged line: applied
  * apply on a changed upstream line: reported, not applied
  * export: the string rows become a JS dictionary and an Android strings.xml

Runs under pytest or unittest:  python3 -m unittest tools.i18n.test_i18n
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tm_common as tm  # noqa: E402
import extract_tm  # noqa: E402
import apply_tm  # noqa: E402
import export_i18n  # noqa: E402


def git(repo, *args):
    subprocess.check_call(
        ["git", "-C", repo] + list(args),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)


BASE_JS = (
    "function greet() {\n"
    "  // 向使用者問好\n"
    "  const msg = \"你好\";\n"
    "  return msg;\n"
    "}\n"
    "const TABLE = {\n"
    "  greet: { zh: '你好', en: 'Hello' },\n"
    "};\n"
)

TRANSLATED_JS = (
    "function greet() {\n"
    "  // greet the user\n"
    "  const msg = \"Hello\";\n"
    "  return msg;\n"
    "}\n"
    "const TABLE = {\n"
    "  greet: { zh: '你好', en: 'Hello' },\n"
    "};\n"
)

# the changed-upstream variant: the comment line keeps its shape but the
# Chinese text moved, so the memory row no longer matches it.
CHANGED_UPSTREAM_JS = (
    "function greet() {\n"
    "  // 問好 (rewritten upstream)\n"
    "  const msg = \"Hello\";\n"
    "  return msg;\n"
    "}\n"
    "const TABLE = {\n"
    "  greet: { zh: '你好', en: 'Hello' },\n"
    "};\n"
)


class FakeRepoMixin:
    def _mkrepo(self):
        self.repo = tempfile.mkdtemp(prefix="tm_test_")
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.email", "t@example.com")
        git(self.repo, "config", "user.name", "t")
        return self.repo

    def _commit_js(self, content, subject):
        path = os.path.join(self.repo, "app.js")
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        git(self.repo, "add", "app.js")
        git(self.repo, "commit", "-q", "-m", subject)

    def tearDown(self):
        if getattr(self, "repo", None):
            shutil.rmtree(self.repo, ignore_errors=True)


class ExtractTest(FakeRepoMixin, unittest.TestCase):
    def test_extract_rows(self):
        self._mkrepo()
        self._commit_js(BASE_JS, "base: add the app")
        self._commit_js(TRANSLATED_JS, "S-23: translate the app to English")

        out = os.path.join(self.repo, "tools", "i18n", "s23-zh-en.tsv")
        extract_tm.main(["--repo", self.repo, "--ref", "HEAD", "--out", out])
        rows = tm.read_tsv(out)

        by_zh = {r["zh"]: r for r in rows}
        # comment line
        self.assertIn("// 向使用者問好", by_zh)
        self.assertEqual(by_zh["// 向使用者問好"]["kind"], "comment")
        self.assertEqual(by_zh["// 向使用者問好"]["context"], "greet")
        self.assertEqual(by_zh["// 向使用者問好"]["en"], "// greet the user")
        # string line
        self.assertIn('const msg = "你好";', by_zh)
        self.assertEqual(by_zh['const msg = "你好";']["kind"], "string")
        self.assertEqual(by_zh['const msg = "你好";']["en"], 'const msg = "Hello";')
        # the dict line was not changed (already bilingual) so it has no row
        self.assertNotIn("greet: { zh: '你好', en: 'Hello' },", by_zh)


class ApplyTest(FakeRepoMixin, unittest.TestCase):
    def _build(self):
        self._mkrepo()
        self._commit_js(BASE_JS, "base: add the app")
        self._commit_js(TRANSLATED_JS, "S-23: translate the app to English")
        out = os.path.join(self.repo, "tools", "i18n", "s23-zh-en.tsv")
        extract_tm.main(["--repo", self.repo, "--ref", "HEAD", "--out", out])
        self.rows = tm.read_tsv(out)

    def test_apply_unchanged_line(self):
        self._build()
        # a tree still carrying the old Chinese: reset app.js to the base side
        with open(os.path.join(self.repo, "app.js"), "w", encoding="utf-8") as f:
            f.write(BASE_JS)
        applied, unmatched = apply_tm.apply_to_tree(self.repo, self.rows, write=True)
        with open(os.path.join(self.repo, "app.js"), encoding="utf-8") as f:
            result = f.read()
        self.assertEqual(result, TRANSLATED_JS)
        self.assertEqual(len(unmatched), 0)
        self.assertGreaterEqual(len(applied), 2)

    def test_apply_changed_upstream_line(self):
        self._build()
        # upstream moved the comment line: the memory row no longer matches
        with open(os.path.join(self.repo, "app.js"), "w", encoding="utf-8") as f:
            f.write(CHANGED_UPSTREAM_JS)
        before = CHANGED_UPSTREAM_JS
        applied, unmatched = apply_tm.apply_to_tree(self.repo, self.rows, write=True)
        with open(os.path.join(self.repo, "app.js"), encoding="utf-8") as f:
            after = f.read()
        # the moved line is reported, not silently applied
        self.assertTrue(any(r["zh"] == "// 向使用者問好" for r in unmatched))
        # the changed line is left untouched
        self.assertEqual(after, before)


class ExportTest(FakeRepoMixin, unittest.TestCase):
    def test_export(self):
        self._mkrepo()
        self._commit_js(BASE_JS, "base: add the app")
        self._commit_js(TRANSLATED_JS, "S-23: translate the app to English")
        out = os.path.join(self.repo, "tools", "i18n", "s23-zh-en.tsv")
        extract_tm.main(["--repo", self.repo, "--ref", "HEAD", "--out", out])

        js_path = os.path.join(self.repo, "s23-i18n.js")
        xml_path = os.path.join(self.repo, "strings-en.xml")
        export_i18n.main(["--repo", self.repo, "--tm", out,
                          "--out-js", js_path, "--out-xml", xml_path])
        with open(js_path, encoding="utf-8") as f:
            js = f.read()
        with open(xml_path, encoding="utf-8") as f:
            xml = f.read()
        # only the string row is exported (the comment row is not)
        self.assertIn('const msg = "Hello";', js)
        self.assertNotIn("greet the user", js)
        self.assertIn("<string name=", xml)
        self.assertIn("Hello", xml)


if __name__ == "__main__":
    unittest.main()
