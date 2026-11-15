#!/usr/bin/env python3
"""
A SerialFrameCodec unit test runner (zero dependencies, no JUnit/gradle/SDK).

Same layout as scripts/test-apivalidator.py: the `--offline` build must not break,
so no gradle `test` task. The difference: SerialFrameCodec only touches java.*,
so even the android.jar stub does not need to be found. Only javac/java in the PATH is needed.

Flow: javac compiles SerialFrameCodec + SerialFrameCodecTest, then runs main().
A non-zero exit is a failure (suitable for CI).

Usage: python scripts/test-serialcodec.py
"""
import os
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
        raise SystemExit("javac/java not found. Install JDK 11+ and add it to the PATH")

    src = ROOT / "sdk-module" / "hardware-direct" / "src" / "main" / "java" / "com" / "ubtechinc" / "alpha" / "hardware"
    test = ROOT / "sdk-module" / "hardware-direct" / "src" / "test" / "java" / "com" / "ubtechinc" / "alpha" / "hardware" / "SerialFrameCodecTest.java"
    assert (src / "SerialFrameCodec.java").exists(), f"source file missing: {src}"
    assert test.exists(), f"test file missing: {test}"

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="serialcodec-test-"))
    # SerialFrameCodec is pure java.* (java.nio/java.util). No classpath is needed.
    files = [
        str(src / "SerialFrameCodec.java"),
        str(test),
    ]
    cp = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files
    r = subprocess.run(cp, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("compilation failed")

    r = subprocess.run(
        [java, "-cp", str(tmp), "com.ubtechinc.alpha.hardware.SerialFrameCodecTest"],
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
