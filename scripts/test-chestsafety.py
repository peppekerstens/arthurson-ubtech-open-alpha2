#!/usr/bin/env python3
"""
ChestSafety vector test runner (zero dependencies, no JUnit/gradle/SDK).

Same shape as scripts/test-serialcodec.py: the project builds offline,
so the test does not run through a gradle test task. ChestSafety is pure
java.*, so the runner only needs javac/java in PATH.

Flow: javac compiles ChestSafety + ChestSafetyVectorTest into a temp dir,
then it runs the main() runner. A non-zero exit means failure. With no
argument the run also executes the negative self-tests of the strict
allow_sit_down flag (alpha2-brain#61).

Usage: python3 scripts/test-chestsafety.py [path to the vector file]

The default vector file is tests/chest_guard_vectors.json in this repo.
It is a copy of alpha2-python tests/chest_guard_vectors.json, the single
source of truth. A missing vector file is a failure, not a skip.
"""
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VECTORS = ROOT / "tests" / "chest_guard_vectors.json"
TEST_CLASS = "com.ubtechinc.alpha.hardware.ChestSafetyVectorTest"


def run_test_class(java: str, classes_dir, vector_file) -> subprocess.CompletedProcess:
    """Run the compiled main() runner on one vector file, capture its output."""
    return subprocess.run(
        [java, "-cp", str(classes_dir), TEST_CLASS, str(vector_file)],
        capture_output=True, text=True,
    )


def check_strict_flag(java: str, classes_dir, label: str, vector_file) -> bool:
    """One negative self-test (alpha2-brain#61).

    A bad allow_sit_down value must fail the run with exit 1 and print
    the expected strict flag FAIL line on stdout+stderr. Other lines,
    for example from the JVM, are ignored. Returns True when the check
    passes.
    """
    r = run_test_class(java, classes_dir, vector_file)
    lines = [ln for ln in (r.stdout + r.stderr).splitlines() if ln.strip()]
    want = "FAIL: case 0 has a bad allow_sit_down value"
    if r.returncode != 1:
        print(f"FAIL strict flag: {label}: exit={r.returncode}, want 1")
        return False
    if not any(ln.startswith(want) for ln in lines):
        print(f"FAIL strict flag: {label}: no line with prefix {want!r}: {lines}")
        return False
    print(f"PASS strict flag: {label}")
    return True


def strict_flag_selftest(java: str, classes_dir) -> bool:
    """Self-tests for the strict allow_sit_down flag (alpha2-brain#61).

    Mutates a copy of the vector file: case 0 with a string flag, then
    case 0 with the key deleted. Both must fail the run cleanly. Reuses
    the compiled classes. Returns True when both checks pass.
    """
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="chestsafety-strict-"))
    ok = True
    try:
        data = json.loads(VECTORS.read_text(encoding="utf-8"))
        data["cases"][0]["allow_sit_down"] = "yes"
        bad = tmp / "string_flag.json"
        bad.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        if not check_strict_flag(java, classes_dir, "string value", bad):
            ok = False

        data = json.loads(VECTORS.read_text(encoding="utf-8"))
        del data["cases"][0]["allow_sit_down"]
        bad = tmp / "missing_key.json"
        bad.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        if not check_strict_flag(java, classes_dir, "missing key", bad):
            ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return ok


def main() -> int:
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("javac/java not found in PATH. Install a JDK (8 or later)")

    src = ROOT / "sdk-module" / "hardware-direct" / "src" / "main" / "java" / "com" / "ubtechinc" / "alpha" / "hardware"
    test = ROOT / "sdk-module" / "hardware-direct" / "src" / "test" / "java" / "com" / "ubtechinc" / "alpha" / "hardware" / "ChestSafetyVectorTest.java"
    assert (src / "ChestSafety.java").exists(), f"source file missing: {src}"
    assert test.exists(), f"test file missing: {test}"

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="chestsafety-test-"))
    files = [
        str(src / "ChestSafety.java"),
        str(test),
    ]
    r = subprocess.run(
        [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files,
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("compile failed")

    argv = sys.argv[1:]
    explicit = bool(argv)
    if not argv:
        argv = [str(VECTORS)]
    r = subprocess.run(
        [java, "-cp", str(tmp), TEST_CLASS] + argv,
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    code = r.returncode
    print("PASS" if code == 0 else "FAIL")
    # alpha2-brain#61: with no argument, also run the strict flag
    # self-tests on the compiled classes before cleaning up.
    if not explicit and code == 0:
        if not strict_flag_selftest(java, tmp):
            code = 1
    shutil.rmtree(tmp, ignore_errors=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
