#!/usr/bin/env python3

import argparse
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP = "dev.corevision.sample"
RUNNER = f"{APP}.test/dev.corevision.android.AcceptanceInstrumentation"
FIXTURE = "files/corevision-validation"
INSTRUMENTATION_TIMEOUT = 165


def adb_command(args, *command):
    prefix = ["adb", "-s", args.serial] if args.serial else ["adb"]
    return [*prefix, *command]


def adb(args, *command, data=None, timeout=30):
    return subprocess.run(adb_command(args, *command), input=data, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          check=True, timeout=timeout).stdout


def text(args, *command, timeout=30):
    return adb(args, *command, timeout=timeout).decode("utf-8", errors="replace").strip()


def iou(observed, expected):
    intersection = union = 0
    for sample, truth in zip(observed, expected):
        foreground = sample > 128
        reference = truth > 128
        intersection += foreground and reference
        union += foreground or reference
    return intersection / union if union else 1.0


def compare(report_dir, baseline, pixels):
    expected = (baseline / "mask.i32le").read_bytes()
    reference_rgba = (baseline / "foreground.rgba").read_bytes()
    alpha = (report_dir / "alpha.u8").read_bytes()
    coarse = (report_dir / "coarse.u8").read_bytes()
    actual_rgba = (report_dir / "foreground.rgba").read_bytes()
    if len(alpha) != pixels or len(coarse) != pixels or any(
        len(data) != pixels * 4 for data in (expected, actual_rgba, reference_rgba)
    ):
        raise ValueError("Incorrect mask or foreground byte length")
    reference = [value for value, in struct.iter_unpack("<i", expected)]
    if actual_rgba[3::4] != alpha:
        raise ValueError("Foreground alpha does not match the 302 mask")
    for channel in range(3):
        if any(value > coverage for value, coverage in zip(actual_rgba[channel::4], alpha)):
            raise ValueError("Foreground RGB is not premultiplied")
    return {
        "threshold": ">128",
        "scene301_iou": iou(coarse, reference),
        "scene302_iou": iou(alpha, reference),
        "foreground_rgba_mae": sum(abs(actual - expected) for actual, expected in zip(actual_rgba, reference_rgba)) / len(actual_rgba),
    }


def run_instrumentation(args, options, log):
    if args.worker_fault is None:
        result = text(args, "shell", "am", "instrument", "-w", "-r", *options, RUNNER, timeout=INSTRUMENTATION_TIMEOUT)
        log.write_text(result + "\n", encoding="utf-8")
        return result

    signal, error = ("KILL", "WORKER_FAILED") if args.worker_fault == "kill" else ("STOP", "TIMEOUT")
    command = adb_command(args, "shell", "am", "instrument", "-w", "-r", *options, "-e", "expectWorkerError", error, RUNNER)
    with log.open("wb") as output, subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT) as process:
        try:
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline and process.poll() is None:
                rows = text(args, "shell", "ps", "-A", "-w", "-o", "PID,PPID,ARGS", timeout=5).splitlines()
                processes = [fields for row in rows if len(fields := row.split(maxsplit=2)) == 3]
                launchers = {pid for pid, parent_pid, command_line in processes
                             if APP in command_line and "libcorevision_probe.so --supervise" in command_line}
                samples = [pid for pid, parent_pid, command_line in processes
                           if pid.isdigit() and parent_pid in launchers
                           and "libcorevision_musl.so --library-path" in command_line]
                if len(samples) == 1:
                    worker = samples[0]
                    text(args, "shell", "run-as", APP, "kill", f"-{signal}", worker, timeout=5)
                    process.wait(timeout=INSTRUMENTATION_TIMEOUT)
                    break
                time.sleep(0.08)
            else:
                raise ValueError("Could not identify the sample's native worker for the fault test")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
                try:
                    text(args, "shell", "am", "force-stop", APP, timeout=5)
                except (OSError, subprocess.SubprocessError):
                    pass
    result = log.read_text(encoding="utf-8")
    if "INSTRUMENTATION_CODE: -1" in result:
        rows = text(args, "shell", "ps", "-A", "-w", "-o", "PID,ARGS", timeout=5).splitlines()
        if any(row.lstrip().startswith(worker + " ") and "libcorevision_musl.so --library-path" in row for row in rows):
            raise ValueError("Faulted native worker was not reaped")
    return result


def push_fixture(args, fixture, name):
    rgba = fixture.read_bytes()
    if len(rgba) != args.width * args.height * 4:
        raise ValueError("Fixture dimensions do not match the input RGBA byte length")
    adb(args, "shell", "-T", f"run-as {APP} sh -c 'cat > {FIXTURE}/{name}'", data=rgba)
    return hashlib.sha256(rgba).hexdigest()


def validate(args, report_dir):
    if text(args, "shell", "getenforce") != "Enforcing" or text(args, "shell", "id", "-u") != "2000":
        raise ValueError("Acceptance requires SELinux Enforcing and an unprivileged ADB shell")
    if text(args, "shell", "getconf", "PAGE_SIZE") != "4096" or text(args, "shell", "getprop", "ro.product.cpu.abi") != "arm64-v8a":
        raise ValueError("Acceptance requires ARM64 with 4 KiB pages")
    metadata = {
        "android_api": text(args, "shell", "getprop", "ro.build.version.sdk"),
        "build_type": text(args, "shell", "getprop", "ro.build.type"),
        "emulator": text(args, "shell", "getprop", "ro.kernel.qemu") == "1",
        "selinux": "Enforcing",
        "abi": "arm64-v8a",
        "page_size": 4096,
        "offline": "Active default network: none" in text(args, "shell", "dumpsys", "connectivity"),
    }
    print(text(args, "install", "-r", str(args.apk), timeout=180))
    print(text(args, "install", "-r", str(args.test_apk), timeout=60))
    mode = "matting-only" if args.expect_face_models_missing else "probe"
    fixture = args.face_input
    second_fixture = None
    if args.baseline is not None:
        mode, fixture = "baseline", args.baseline / "input.rgba"
    elif args.compare_input is not None:
        mode = "comparison"
        fixture, second_fixture = args.compare_input
    elif fixture is not None:
        mode = "faces"
    options = ["-e", "mode", mode]
    if fixture is not None:
        text(args, "shell", "run-as", APP, "mkdir", "-p", FIXTURE)
        metadata["input_sha256"] = push_fixture(args, fixture, "input.rgba")
        options += ["-e", "width", str(args.width), "-e", "height", str(args.height)]
    if args.face_count is not None:
        options += ["-e", "faceCount", str(args.face_count)]
    if second_fixture is not None:
        push_fixture(args, second_fixture, "second.rgba")
        options += ["-e", "expectMatch", str(args.expect_match == "yes").lower()]

    result = run_instrumentation(args, options, report_dir / "instrumentation.log")
    if "INSTRUMENTATION_RESULT: corevision=PASS " not in result or "INSTRUMENTATION_CODE: -1" not in result:
        raise ValueError(f"Instrumentation failed. See {report_dir / 'instrumentation.log'}")
    metadata["probe"] = "passed"
    if args.face_count is not None:
        metadata["face_count"] = args.face_count
        metadata["blank_face_count"] = 0
    if second_fixture is not None:
        prefix = "INSTRUMENTATION_RESULT: similarity="
        scores = [line.removeprefix(prefix) for line in result.splitlines() if line.startswith(prefix)]
        if len(scores) != 1:
            raise ValueError(f"Missing face similarity. See {report_dir / 'instrumentation.log'}")
        metadata["face_similarity"] = float(scores[0])
        metadata["same_person"] = args.expect_match == "yes"
    if args.expect_face_models_missing:
        metadata["face_models_missing"] = "passed"
    if args.worker_fault is not None:
        metadata["worker_fault"] = args.worker_fault
        metadata["restarted_after_fault"] = True
    if args.baseline is not None:
        for filename in ("alpha.u8", "coarse.u8", "foreground.rgba", "runtime.txt"):
            path = f"{FIXTURE}/{filename}"
            (report_dir / filename).write_bytes(adb(args, "exec-out", "run-as", APP, "cat", path))
        metadata.update(compare(report_dir, args.baseline, args.width * args.height))
        runtime = (report_dir / "runtime.txt").read_text(encoding="utf-8")
        if "cpu=MS_CPU\n" not in runtime or "sha256=" not in runtime:
            raise ValueError("Runtime report does not include CPU configuration and loaded hashes")
        for key in ("cpu", "cpuConfigurations", "scene301Score", "scene302Score", "workerMillis", "workerPeakRssKiB",
                    "firstAarMillis", "secondAarMillis"):
            for line in runtime.splitlines():
                if line.startswith(key + "="):
                    metadata[key] = line.split("=", 1)[1]
                    break
    (report_dir / "report.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    if args.baseline is not None and (metadata["scene301_iou"] < 0.985 or metadata["scene302_iou"] < 0.995
                                      or metadata["foreground_rgba_mae"] > 2.0):
        raise ValueError(f"Output differs from the HarmonyOS baseline. See {report_dir / 'report.json'}")
    print(json.dumps({key: value for key, value in metadata.items() if key != "input_sha256"}, indent=2))


def main():
    parser = argparse.ArgumentParser(description="Validate a Core Vision AAR using the sample APK and an optional RGBA baseline")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--apk", type=Path, default=ROOT / "sample/build/outputs/apk/debug/sample-debug.apk")
    parser.add_argument("--test-apk", type=Path, default=ROOT / "sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk")
    fixtures = parser.add_mutually_exclusive_group()
    fixtures.add_argument("--baseline", type=Path)
    fixtures.add_argument("--face-input", type=Path, help="test face detection with a packed RGBA fixture")
    fixtures.add_argument("--compare-input", type=Path, nargs=2, metavar=("FIRST", "SECOND"), help="compare two equal-size RGBA fixtures")
    fixtures.add_argument("--expect-face-models-missing", action="store_true", help="test a matting-only APK")
    parser.add_argument("--face-count", type=int, help="expected number of faces in --face-input")
    parser.add_argument("--expect-match", choices=("yes", "no"), help="expected decision from --compare-input")
    parser.add_argument("--width", type=int, default=1536)
    parser.add_argument("--height", type=int, default=1024)
    parser.add_argument("--worker-fault", choices=("kill", "stop"), help="verify recovery after worker death or deadline expiry")
    parser.add_argument("--report-dir", type=Path)
    args = parser.parse_args()
    if args.worker_fault is not None and args.baseline is None:
        parser.error("--worker-fault requires --baseline")
    if (args.face_count is None) != (args.face_input is None) or (args.face_count is not None and args.face_count < 0):
        parser.error("--face-input and a nonnegative --face-count must be supplied together")
    if (args.expect_match is None) != (args.compare_input is None):
        parser.error("--compare-input and --expect-match must be supplied together")
    report_dir = args.report_dir or Path(tempfile.mkdtemp(prefix="corevision-device-"))
    report_dir = report_dir.resolve()
    if report_dir == ROOT or ROOT in report_dir.parents:
        parser.error("Use a report directory outside the source tree")
    report_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    print(f"Local report directory: {report_dir}", flush=True)
    try:
        validate(args, report_dir)
    except subprocess.CalledProcessError as error:
        (report_dir / "adb.log").write_bytes(error.stdout or b"")
        print(f"ADB failed. See {report_dir / 'adb.log'}", file=sys.stderr)
        return 1
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
