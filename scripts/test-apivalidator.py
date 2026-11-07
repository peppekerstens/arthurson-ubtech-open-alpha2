#!/usr/bin/env python3
"""
A pure Java unit test runner (zero dependencies, no JUnit/gradle).

See the header of app/src/test/java/com/open/alpha2/ApiValidatorTest.java for the reason:
the gradle cache has no junit, and the `--offline` build must not break.


Flow: find android.jar (the SDK) + javac -> compile the class under test + the Test
-> run main() -> a non-zero exit = failure (suitable for CI).
Three suites: ApiValidatorTest (the ApiValidator closure) + ColorTrackTest
(ColorTrackLogic single file) + MusicEqTest (MusicEq single file; the last two do not even need android.jar).

Also: StopWordMatcherTest, ServoStreamCoreTest, MicSlotsTest, SleepModePolicyTest and
DirectChestControllerTest (alpha2-brain#30, pure java.* with stubs in scripts/test-stubs).
ChestReplyMatchTest (alpha2-board-sim#12): the chest VERSION reply match.
VoiceTimingTest (voice turn time log, queue item 6).
HeadKeyClickTest (head key click codes 0x3c01/0x3d01) and WakeCommandMatchTest (local
command after the wake word), robot session 2026-11-01.
StopEchoGuardTest (alpha2-brain#52): the stop echo guard time windows.

Usage: python scripts/test-apivalidator.py
"""
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def find_sdk() -> pathlib.Path:
    cands = []
    # 1. local.properties (this machine; not in the repo)
    lp = ROOT / "local.properties"
    if lp.exists():
        m = re.search(r"sdk\.dir\s*=\s*(.+)", lp.read_text(encoding="utf-8"))
        if m:
            cands.append(pathlib.Path(m.group(1).strip()))
    # 2. Environment variable (CI uses this)
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(env):
            cands.append(pathlib.Path(os.environ[env]))
    # 3. Common locations
    home = pathlib.Path.home()
    cands += [
        home / "AppData" / "Local" / "Android" / "Sdk",
        home / "Android" / "Sdk",
        pathlib.Path("/usr/lib/android-sdk"),
        pathlib.Path("/opt/android-sdk"),
    ]
    for c in cands:
        if c and (c / "platforms").is_dir():
            return c
    raise SystemExit("Android SDK not found (tried local.properties / ANDROID_SDK_ROOT / common paths)")


def find_android_jar(sdk: pathlib.Path) -> pathlib.Path:
    # Follow the compileSdkVersion of app/build.gradle; if not found, use the highest version (it is a stub; any version works)
    want = "android-25"
    gradle = (ROOT / "app" / "build.gradle").read_text(encoding="utf-8")
    m = re.search(r"compileSdkVersion\s+(\d+)", gradle)
    if m:
        want = "android-" + m.group(1)
    plats = sorted(
        (p for p in (sdk / "platforms").iterdir() if (p / "android.jar").exists()),
        key=lambda p: p.name,
    )
    if not plats:
        raise SystemExit(f"no android.jar inside {sdk}/platforms")
    for p in plats:
        if p.name == want:
            return p / "android.jar"
    print(f"Note: {want} is missing; {plats[-1].name} stands in for it (only a stub; no effect)");
    return plats[-1] / "android.jar"


def main() -> int:
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("javac/java not found; install JDK 11+ and add it to the PATH")
    sdk = find_sdk()
    jar = find_android_jar(sdk)
    print(f"SDK: {sdk}  android.jar: {jar.name}")

    src = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2"
    test = ROOT / "app" / "src" / "test" / "java" / "com" / "open" / "alpha2" / "ApiValidatorTest.java"
    assert test.exists(), f"test file gone: {test}"

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="apivalidator-test-"))
    # The ApiValidator -> HttpServer -> WebSocketServer -> EventBus closure
    # only touches android.util/Log + java.*; compile them together (no need for the whole app).
    # After the 2026-09 cross-cutting simplification, HttpServer/EventBus share JsonUtil/IOUtil; compile them together.
    files = [
        str(src / "ApiValidator.java"),
        str(src / "JsonUtil.java"),
        str(src / "IOUtil.java"),
        str(src / "HttpServer.java"),
        str(src / "WebSocketServer.java"),
        str(src / "EventBus.java"),
        # alpha2-brain#24: ApiValidator uses the chest "Sit down" guard (pure java.*).
        str(ROOT / "sdk-module" / "hardware-direct" / "src" / "main" / "java" / "com" / "ubtechinc"
            / "alpha" / "hardware" / "ChestSafety.java"),
        str(test),
    ]
    cp = [javac, "-encoding", "UTF-8", "-nowarn", "-cp", str(jar), "-d", str(tmp)] + files
    r = subprocess.run(cp, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("compilation failed")

    r = subprocess.run(
        [java, "-cp", str(tmp) + os.pathsep + str(jar), "com.open.alpha2.ApiValidatorTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # The second suite: ColorTrackTest (ColorTrackLogic is pure java.*; no android stub).
    testdir = ROOT / "app" / "src" / "test" / "java" / "com" / "open" / "alpha2"
    files2 = [
        str(src / "ColorTrackLogic.java"),
        str(testdir / "ColorTrackTest.java"),
    ]
    cp2 = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files2
    r = subprocess.run(cp2, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("compilation failed (ColorTrackTest)")
    r = subprocess.run(
        [java, "-cp", str(tmp), "com.open.alpha2.ColorTrackTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # The third suite: MusicEqTest (MusicEq is pure java.*; no android stub).
    files3 = [
        str(src / "MusicEq.java"),
        str(testdir / "MusicEqTest.java"),
    ]
    cp3 = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files3
    r = subprocess.run(cp3, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("compilation failed (MusicEqTest)")
    r = subprocess.run(
        [java, "-cp", str(tmp), "com.open.alpha2.MusicEqTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # Suite 4: StopWordMatcherTest (pure java.*, no android stub). alpha2-brain#21.
    files4 = [
        str(src / "StopWordMatcher.java"),
        str(testdir / "StopWordMatcherTest.java"),
    ]
    cp4 = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files4
    r = subprocess.run(cp4, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("compile failed (StopWordMatcherTest)")
    r = subprocess.run(
        [java, "-cp", str(tmp), "com.open.alpha2.StopWordMatcherTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # Suites 5 and later (alpha2-brain#30): pure java.* plus small stubs of android.util.Log
    # in scripts/test-stubs (no android.jar on the class path: the stubs do not throw).
    hw = ROOT / "sdk-module" / "hardware-direct" / "src" / "main" / "java" / "com" / "ubtechinc" / "alpha" / "hardware"
    hwtest = ROOT / "sdk-module" / "hardware-direct" / "src" / "test" / "java" / "com" / "ubtechinc" / "alpha" / "hardware"
    stubs = ROOT / "scripts" / "test-stubs"
    suites = [
        ("com.open.alpha2.ServoStreamCoreTest",
         [src / "ServoStreamCore.java", src / "JsonUtil.java", hw / "ChestSafety.java",
          testdir / "ServoStreamCoreTest.java"]),
        ("com.open.alpha2.ChestReplyMatchTest",
         [src / "ChestReplyMatch.java", testdir / "ChestReplyMatchTest.java"]),
        ("com.open.alpha2.MicSlotsTest",
         [src / "MicSlots.java", testdir / "MicSlotsTest.java"]),
        ("com.open.alpha2.SleepModePolicyTest",
         [src / "SleepModePolicy.java", testdir / "SleepModePolicyTest.java"]),
        ("com.open.alpha2.VoiceTimingTest",
         [src / "VoiceTiming.java", testdir / "VoiceTimingTest.java"]),
        ("com.ubtechinc.alpha.hardware.HeadKeyClickTest",
         [hw / "HeadKeyClick.java", hwtest / "HeadKeyClickTest.java"]),
        ("com.open.alpha2.WakeCommandMatchTest",
         [src / "WakeCommandMatch.java", testdir / "WakeCommandMatchTest.java"]),
        ("com.open.alpha2.StopEchoGuardTest",
         [src / "StopEchoGuard.java", src / "StopWordMatcher.java", testdir / "StopEchoGuardTest.java"]),
        ("com.ubtechinc.alpha.hardware.DirectChestControllerTest",
         [hw / "DirectChestController.java", hw / "DirectSerialPort.java", hw / "DirectSerialConfig.java",
          hw / "RobotWire.java", hw / "SerialFrameCodec.java", hw / "ChestSafety.java",
          hw / "DeveloperPacketData.java",
          stubs / "android" / "util" / "Log.java", hwtest / "DirectChestControllerTest.java"]),
    ]
    for main_class, srcs in suites:
        out = tmp / main_class
        out.mkdir()
        cp5 = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(out)] + [str(f) for f in srcs]
        r = subprocess.run(cp5, capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout)
            print(r.stderr)
            shutil.rmtree(tmp, ignore_errors=True)
            raise SystemExit("compile failed (" + main_class + ")")
        r = subprocess.run([java, "-cp", str(out), main_class], capture_output=True, text=True,
                           cwd=str(ROOT))
        print(r.stdout, end="")
        if r.stderr:
            print(r.stderr, end="", file=sys.stderr)
        if r.returncode != 0:
            shutil.rmtree(tmp, ignore_errors=True)
            print("FAIL")
            return r.returncode
    shutil.rmtree(tmp, ignore_errors=True)
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
