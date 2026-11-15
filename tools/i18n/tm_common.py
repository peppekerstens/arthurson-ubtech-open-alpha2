"""Shared helpers for the S-23 translation-memory tools.

Python 3 standard library only.

This module holds the pieces that more than one tool needs:
  * Chinese text detection
  * file type and line classification (kind + context)
  * the S-23 commit detector
  * TSV read/write

The "kind" of a line is one of: comment, string, dict, doc.
The "context" of a line is the key of a dictionary entry, or the nearest
function (or method), or the nearest HTML id, or the nearest spec key.
"""

import re

# Characters that mark a line as carrying Chinese text.
_CJK_RE = re.compile(
    "[\u3400-\u4dbf"     # CJK Extension A
    "\u4e00-\u9fff"      # CJK Unified Ideographs
    "\uf900-\ufaff"      # CJK Compatibility Ideographs
    "\u3040-\u30ff"      # Hiragana + Katakana
    "\uac00-\ud7af"      # Hangul Syllables
    "]")


def has_chinese(text):
    """True if the text carries at least one Chinese (or CJK) character."""
    return bool(_CJK_RE.search(text))


def file_lang(path):
    """Map a path to a coarse language tag used by the classifier."""
    p = path.lower()
    if p.endswith((".js", ".mjs", ".cjs")):
        return "js"
    if p.endswith(".java"):
        return "java"
    if p.endswith(".py"):
        return "py"
    if p.endswith(".sh"):
        return "sh"
    if p.endswith((".yml", ".yaml")):
        return "yml"
    if p.endswith(".json"):
        return "json"
    if p.endswith(".xml"):
        return "xml"
    if p.endswith((".html", ".htm")):
        return "html"
    if p.endswith(".css"):
        return "css"
    if p.endswith(".txt"):
        return "cmake"
    if p.endswith(".md"):
        return "md"
    return "other"


# A commit subject is an S-23 translation commit when it names the work.
# The nine real commits spell it as "S-23", "s23", or "translate slice NN to
# English". This catches all of them and none of the app commits.
_S23_RE = re.compile(
    r"(?i)(s-?23|translate\s+slice|slice\s+\d+.*english|english\s+text\s+of\s+slice)")


def is_s23_subject(subject):
    """True if a commit subject marks an S-23 translation commit."""
    return bool(_S23_RE.search(subject))


# --- line classification -------------------------------------------------

_JS_FUNC_RE = [
    re.compile(r"^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+\*?\s*([A-Za-z_$][\w$]*)"),
    re.compile(r"^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s+)?(?:function\b|\([^)]*\)\s*=>|\w[\w$]*\s*=>)"),
    re.compile(r"^\s*(?:export\s+)?(?:async\s+)?([A-Za-z_$][\w$]*)\s*=\s*(?:function\b|\([^)]*\)\s*=>)"),
    re.compile(r"^\s*([A-Za-z_$][\w$]*)\s*:\s*(?:async\s+)?function\b"),
    re.compile(r"^\s*(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{\s*$"),
]

_KEYWORDS = {
    "if", "for", "while", "switch", "catch", "return", "function", "else",
    "do", "try", "new", "typeof", "void", "delete", "in", "of", "await",
    "case", "default", "break", "continue", "throw", "yield", "const",
    "let", "var", "public", "private", "protected", "static", "final",
    "synchronized", "abstract", "native", "class", "extends", "implements",
    "import", "package", "return", "else", "elif", "except", "finally",
    "with", "lambda", "assert", "global", "nonlocal", "pass", "raise",
    "not", "and", "or", "is", "as", "from", "def", "async", "yield",
}

_PY_DEF_RE = re.compile(r"^\s*(?:async\s+)?(?:def|class)\s+([A-Za-z_$][\w$]*)")
_JAVA_METHOD_RE = re.compile(r"([A-Za-z_$][\w$]*)\s*\([^;]*\)\s*(?:throws\s+[\w.,\s]+)?\{\s*$")
_HTML_ID_RE = re.compile(r"\bid\s*=\s*[\"']([A-Za-z0-9_-]+)[\"']")
_YAML_KEY_RE = re.compile(r"^\s*([A-Za-z0-9_.$-]+)\s*:")
_JSON_KEY_RE = re.compile(r"^\s*\"([^\"]+)\"\s*:")
_DICT_RE = re.compile(
    r"^\s*(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z_$][\w$]*))\s*:(?!\s*[:/])\s*"
    r"([\"'({][^\n]*)$")


class Scanner:
    """Classify lines of one file.

    Build it with the full old-side content (a list of lines, no trailing
    newlines). Then call classify(i) for a 0-based line index to get the
    (kind, context) of that line.
    """

    def __init__(self, path, lines):
        self.path = path
        self.lang = file_lang(path)
        self.lines = lines
        self.n = len(lines)
        self._precompute()

    # -- per-language precomputation -------------------------------------

    def _precompute(self):
        n = self.n
        self._func = [""] * n
        self._html_id = [""] * n
        self._spec_key = [""] * n
        self._in_block = [False] * n     # inside a /* */ or <!-- --> block
        self._in_doc = [False] * n       # inside a python docstring
        self._block = [False] * n        # the line is a block-comment line

        func = ""
        hid = ""
        skey = ""
        in_block = False
        doc_delim = None

        for i in range(n):
            line = self.lines[i]
            stripped = line.strip()

            # update the "nearest" trackers first so a definition is usable
            # on its own line and on the lines after it.
            if self.lang in ("js",):
                for rx in _JS_FUNC_RE:
                    m = rx.match(line)
                    if m:
                        name = next(g for g in m.groups() if g)
                        if name and name not in _KEYWORDS:
                            func = name
                        break
            elif self.lang == "java":
                m = _JAVA_METHOD_RE.search(line)
                if m and m.group(1) not in _KEYWORDS:
                    func = m.group(1)
            elif self.lang == "py":
                m = _PY_DEF_RE.match(line)
                if m:
                    func = m.group(1)
            elif self.lang == "html":
                m = _HTML_ID_RE.search(line)
                if m:
                    hid = m.group(1)
            elif self.lang in ("yml",):
                m = _YAML_KEY_RE.match(line)
                if m:
                    skey = m.group(1)
            elif self.lang == "json":
                m = _JSON_KEY_RE.match(line)
                if m:
                    skey = m.group(1)

            self._func[i] = func
            self._html_id[i] = hid
            self._spec_key[i] = skey

            # block / docstring state, evaluated for classification of this line
            if self.lang in ("js", "java"):
                if in_block:
                    self._block[i] = True
                    if "*/" in line:
                        in_block = False
                elif "/*" in line:
                    self._block[i] = True
                    if "*/" not in line:
                        in_block = True
                elif stripped.startswith(("*", "*/")):
                    self._block[i] = True
            elif self.lang == "css":
                if in_block:
                    self._block[i] = True
                    if "*/" in line:
                        in_block = False
                elif "/*" in line:
                    self._block[i] = True
                    if "*/" not in line:
                        in_block = True
            elif self.lang == "html":
                if in_block:
                    self._block[i] = True
                    if "-->" in line:
                        in_block = False
                elif "<!--" in line:
                    self._block[i] = True
                    if "-->" not in line:
                        in_block = True
            elif self.lang == "py":
                if doc_delim:
                    self._in_doc[i] = True
                    if doc_delim in line:
                        doc_delim = None
                else:
                    for d in ('"""', "'''"):
                        c = line.count(d)
                        if c >= 1:
                            self._in_doc[i] = True
                            if c == 1:
                                doc_delim = d
                            break
            self._in_block[i] = in_block or self._block[i]

    # -- public ----------------------------------------------------------

    def context_for(self, i, kind):
        if kind == "dict":
            return ""          # filled in by classify from the key
        if self.lang == "html":
            return self._html_id[i]
        if self.lang in ("yml", "json"):
            return self._spec_key[i]
        if self.lang in ("js", "java", "py"):
            return self._func[i]
        return ""

    def classify(self, i):
        """Return (kind, context) for the 0-based line i."""
        line = self.lines[i]
        stripped = line.strip()
        lang = self.lang
        chinese = has_chinese(line)

        # block comment line
        if self._block[i] or (lang == "py" and self._in_doc[i]):
            kind = "comment" if lang != "py" else "doc"
            return kind, self.context_for(i, kind)

        if not chinese:
            # still classify for completeness, but callers only use rows with
            # Chinese on the old side
            return self._code_kind(i, stripped, line), self.context_for(i, self._code_kind(i, stripped, line))

        # line-comment forms
        if lang in ("js", "java", "css") and (
                stripped.startswith("//") or (lang != "css" and stripped.startswith("*"))):
            return "comment", self.context_for(i, "comment")
        if lang in ("py", "sh", "cmake") and stripped.startswith("#"):
            return "comment", self.context_for(i, "comment")

        # dictionary entry: key: value with the value carrying Chinese
        if lang in ("js", "py"):
            m = _DICT_RE.match(line)
            if m and has_chinese(m.group(4)):
                key = m.group(1) or m.group(2) or m.group(3)
                return "dict", key

        # document files: spec / manifest / makefile / markdown
        if lang in ("yml", "json", "xml", "cmake", "md", "other"):
            return "doc", self.context_for(i, "doc")

        # remaining code: it is a string literal (or the closest thing)
        return "string", self.context_for(i, "string")

    def _code_kind(self, i, stripped, line):
        lang = self.lang
        if lang in ("js", "java", "css") and (
                stripped.startswith("//") or (lang != "css" and stripped.startswith("*"))):
            return "comment"
        if lang in ("py", "sh", "cmake") and stripped.startswith("#"):
            return "comment"
        if lang in ("js", "py"):
            m = _DICT_RE.match(line)
            if m and has_chinese(m.group(4)):
                return "dict"
        if lang in ("yml", "json", "xml", "cmake", "md", "other"):
            return "doc"
        return "string"


# --- TSV -----------------------------------------------------------------

TSV_HEADER = ["file", "kind", "context", "zh", "en"]


def read_tsv(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f):
            line = line.rstrip("\n")
            if not line:
                continue
            if n == 0 and line == "\t".join(TSV_HEADER):
                continue
            parts = line.split("\t")
            if len(parts) < len(TSV_HEADER):
                parts += [""] * (len(TSV_HEADER) - len(parts))
            rows.append(dict(zip(TSV_HEADER, parts[:len(TSV_HEADER)])))
    return rows


def write_tsv(path, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\t".join(TSV_HEADER) + "\n")
        for r in rows:
            cells = [r.get(k, "") for k in TSV_HEADER]
            cells = [c.replace("\t", " ").replace("\n", " ") for c in cells]
            f.write("\t".join(cells) + "\n")


# --- S-23 commit discovery ----------------------------------------------

def find_s23_commits(repo, ref="HEAD"):
    """Return the S-23 commit hashes on the first-parent history of ref,
    oldest first. Uses the subject of each commit."""
    out = []
    for line in _git(repo, ["log", "--first-parent", "--format=%H%x00%s", ref]):
        if "\x00" not in line:
            continue
        h, subject = line.split("\x00", 1)
        if is_s23_subject(subject):
            out.append(h)
    out.reverse()  # git log is newest first; we want oldest first
    return out


def _git(repo, args, check=True):
    import subprocess
    proc = subprocess.run(
        ["git", "-C", repo] + args,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if check and proc.returncode != 0:
        raise RuntimeError("git %s failed: %s" % (" ".join(args), proc.stderr.strip()))
    return proc.stdout.splitlines()
