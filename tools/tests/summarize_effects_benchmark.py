#!/usr/bin/env python3
"""Summarize actual Minecraft benchmark CSVs; never substitute simulated FPS/TPS."""
import argparse
import csv
import hashlib
import json
import math
import statistics
import zipfile
from pathlib import Path


def distribution(values, scale=1.0):
    ordered = sorted(value / scale for value in values if value >= 0)
    if not ordered:
        return None
    return {
        "samples": len(ordered),
        "mean": statistics.fmean(ordered),
        "p50": ordered[math.ceil(len(ordered) * .50) - 1],
        "p95": ordered[math.ceil(len(ordered) * .95) - 1],
        "p99": ordered[math.ceil(len(ordered) * .99) - 1],
        "max": ordered[-1],
    }


def rows(path):
    with path.open(encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def normalized_jvm_arguments(report):
    """Per-run evidence/native extraction paths may differ; compare their actual contents."""
    normalized = []
    for argument in report.get("jvmArguments", []):
        if argument.startswith(("-Dgtswn.effectsBenchmark=", "-Dgtswn.hiddenEvidence=")):
            continue
        if argument.startswith("-Djava.library.path="):
            directory = Path(argument.split("=", 1)[1])
            contents = sorted((path.name, hashlib.sha256(path.read_bytes()).hexdigest())
                              for path in directory.glob("*.dll"))
            normalized.append(["nativeLibraries", contents])
        elif argument.startswith("-javaagent:"):
            agent, _, parameter = argument[len("-javaagent:"):].partition("=")
            with zipfile.ZipFile(agent) as archive:
                classes = sorted((name, hashlib.sha256(archive.read(name)).hexdigest())
                                 for name in archive.namelist() if name.endswith(".class"))
            parameter_path = Path(parameter)
            parameter_digest = hashlib.sha256(parameter_path.read_bytes()).hexdigest() if parameter_path.is_file() else parameter
            normalized.append(["javaagentClasses", classes, "parameter", parameter_digest])
        else:
            normalized.append(argument)
    return normalized


def summarize(directory):
    report = json.loads((directory / "report.json").read_text(encoding="utf-8"))
    if report["status"] != "COMPLETED":
        raise ValueError(f"{directory}: benchmark failed: {report.get('failure')}")
    if report.get("initialServerLinkedNodes") != 64 or report.get("initialServerConnectedIncorporations") != 64:
        raise ValueError(f"{directory}: real server connections were not verified as 64 nodes and 64 incorporations")
    frames = rows(directory / "frames.csv")
    ticks = rows(directory / "server-ticks.csv")
    result = {"directory": str(directory), "report": report, "windows": []}
    launch_path = directory.parent / "launch.json"
    if launch_path.exists():
        launch = json.loads(launch_path.read_text(encoding="utf-8"))
        report["coreSha256"] = launch.get("coreSha256")
        result["runtimeArtifactSha256"] = launch.get("runtimeSha256")
    guard_path = directory.parent / "driver-result.json"
    if guard_path.exists():
        guard = json.loads(guard_path.read_text(encoding="utf-8"))
        result["driverEvidence"] = guard
        if (not guard.get("restored") or not guard.get("guardConfirmed")
                or not guard.get("realSwapCalls") or guard.get("foregroundViolations")
                or guard.get("visibleWindowViolations")):
            raise ValueError(f"{directory}: hidden real GPU runtime audit did not pass: {guard}")
    for window in report["windows"]:
        index = window["window"]
        if window["scene"].startswith("heavy_"):
            required = {"linkedClientNodes": 64, "incorporatedClientTiles": 64,
                        "visibleCoverSources": 128, "eligibleHighCoverSources": 128}
            if window["scene"] == "heavy_fixed":
                required.update(linkParticles=192, quantumParticles=512)
            wrong = {key: window.get(key) for key, expected in required.items() if window.get(key) != expected}
            if wrong or any(window.get(key, 0) <= 0 for key in ("linkSubmittedPrimitives", "quantumSubmittedPrimitives")):
                raise ValueError(f"{directory} window {index}: incomplete real render load: {wrong}; {window}")
        frame_rows = [row for row in frames if int(row["window"]) == index]
        tick_rows = [row for row in ticks if int(row["window"]) == index]
        if len(frame_rows) < 2 or len(tick_rows) < 2:
            raise ValueError(f"{directory} window {index}: insufficient real frame/tick samples")
        entry = {"window": index, "scene": window["scene"], "frameMetrics": {}, "serverMetrics": {}}
        for name in (
            "frameIntervalNs", "mainThreadFrameCpuNs", "renderWindowWallNs", "renderWindowCpuNs",
            "linkRenderWallNs", "linkRenderCpuNs", "quantumRenderWallNs", "quantumRenderCpuNs",
        ):
            entry["frameMetrics"][name.replace("Ns", "Ms")] = distribution(
                [int(row[name]) for row in frame_rows], 1e6)
        mean_frame = entry["frameMetrics"]["frameIntervalMs"]["mean"]
        entry["observedClientFramesPerSecond"] = 1000 / mean_frame
        if any("nativeSwapWallNs" in row for row in frame_rows):
            entry["frameMetrics"]["nativeSwapWallMs"] = distribution(
                [int(row.get("nativeSwapWallNs", -1)) for row in frame_rows], 1e6)
        for name in ("linkParticles", "quantumParticles", "linkRenderAllocatedBytes", "quantumRenderAllocatedBytes"):
            entry["frameMetrics"][name] = distribution([int(row[name]) for row in frame_rows])
        unique_client_ticks = {int(row["clientTick"]): row for row in frame_rows}
        for name in ("effectTickWallNs", "effectTickCpuNs"):
            entry["frameMetrics"][name.replace("Ns", "Ms")] = distribution(
                [int(row[name]) for row in unique_client_ticks.values()], 1e6)
        for name in ("serverTickWallNs", "serverTickCpuNs", "startIntervalNs"):
            entry["serverMetrics"][name.replace("Ns", "Ms")] = distribution(
                [int(row[name]) for row in tick_rows], 1e6)
        entry["observedIntegratedServerTicksPerSecond"] = 1000 / entry["serverMetrics"]["startIntervalMs"]["mean"]
        result["windows"].append(entry)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = {
        "scope": "Real per-frame and per-server-tick observations, nearest-rank quantiles. FPS comes from client frame START intervals; observed TPS comes from integrated server START intervals. MSPT is server event window wall time. No GPU duration or dedicated-server TPS attribution. Fixed controlled particle render samples and natural dynamic samples are separate.",
        "runs": [summarize(path) for path in args.runs],
    }
    controls = [run["report"] for run in result["runs"]]
    keys = ("java", "heapMaxBytes", "coreSha256", "glVendor", "glRenderer", "glVersion", "displayWidth", "displayHeight", "settings", "warmupSeconds", "sampleSeconds", "rounds", "controlSceneScope")
    mismatches = [key for key in keys if any(report.get(key) != controls[0].get(key) for report in controls[1:])]
    normalized_arguments = [normalized_jvm_arguments(report) for report in controls]
    if any(arguments != normalized_arguments[0] for arguments in normalized_arguments[1:]):
        mismatches.append("jvmArguments (run paths normalized by content)")
    result["controlMismatches"] = mismatches
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for run in result["runs"]:
        print(f"{run['report']['label']}: {run['report']['glRenderer']}")
        for window in run["windows"]:
            frame = window["frameMetrics"]
            print(f"  {window['window']} {window['scene']}: client {window['observedClientFramesPerSecond']:.2f} FPS, "
                  f"render p95 {frame['renderWindowWallMs']['p95']:.3f} ms, "
                  f"link {frame['linkRenderWallMs']['mean']:.3f} ms, quantum {frame['quantumRenderWallMs']['mean']:.3f} ms, "
                  f"integrated MSPT {window['serverMetrics']['serverTickWallMs']['mean']:.3f}, "
                  f"TPS {window['observedIntegratedServerTicksPerSecond']:.2f}")
    if mismatches:
        raise SystemExit("Control mismatch: " + ", ".join(mismatches))


if __name__ == "__main__":
    main()
