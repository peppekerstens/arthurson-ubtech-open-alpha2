#!/usr/bin/env python3
"""
ChestSafety vector test runner (zero dependencies, no JUnit/gradle/SDK).

Same shape as scripts/test-serialcodec.py: the project builds offline,
so the test does not run through a gradle test task. ChestSafety is pure
java.*, so the runner only needs javac/java in PATH.

Flow: javac compiles ChestSafety + ChestSafetyVectorTest into a temp dir,
then it runs the main() runner. A non-zero exit means failure.

Usage: python3 scripts/test-chestsafety.py [path to the vector file]

The default vector file is tests/chest_guard_vectors.json in this repo.
It is a copy of alpha2-python tests/chest_guard_vectors.json, the single
source of truth. A missing vector file is a failure, not a skip.
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


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
    if not argv:
        argv = [str(ROOT / "tests" / "chest_guard_vectors.json")]
    r = subprocess.run(
        [java, "-cp", str(tmp), "com.ubtechinc.alpha.hardware.ChestSafetyVectorTest"] + argv,
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    shutil.rmtree(tmp, ignore_errors=True)
    print("PASS" if r.returncode == 0 else "FAIL")
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
