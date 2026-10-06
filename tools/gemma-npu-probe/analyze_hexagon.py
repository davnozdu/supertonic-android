#!/usr/bin/env python3
"""Summarize each ordinary-app native invocation; retain failed trials."""
import argparse
import json
from pathlib import Path


def summarize(path):
    runs, current = [], None
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if row.get("backend") != "hexagon":
            continue
        if row["stage"] == "before_load":
            current = {"startUptimeMs": row["uptimeMs"], "baseline": row["details"], "rows": []}
            runs.append(current)
        if current is not None:
            current["rows"].append(row)
    for run in runs:
        rows = run.pop("rows")
        samples = [r["details"] for r in rows if r["stage"] == "memory"]
        result = next((r["details"] for r in rows if r["stage"] == "hexagon_benchmark"), None)
        run["result"] = result
        if result and result.get("mode", "bench") == "bench" and result["exitCode"] == 0:
            run["benchmarks"] = json.loads(result["stdout"])
        run["success"] = any(r["stage"] == "complete" for r in rows) and not any(
            r["stage"] in ("failed", "budget_stop", "cancelled") for r in rows)
        run["failures"] = [r for r in rows if r["stage"] in ("failed", "budget_stop", "cancelled")]
        run["samples"] = len(samples)
        for key in ("pssKiB", "rssKiB", "vmSwapKiB", "ionUsedKiB", "gpuUsedKiB"):
            values = [s[key] for s in samples if s.get(key) is not None]
            run["peak_" + key] = max(values) if values else None
        values = [s["memAvailableKiB"] for s in samples]
        run["min_memAvailableKiB"] = min(values) if values else None
        run["afterBenchmark"] = next((r["details"] for r in rows if r["stage"] == "after_benchmark"), None)
    return {"source": str(path), "notes": [
        "CPU time is process-wide and covers native initialization, warmup and every repetition.",
        "CPU time is not energy; memory peaks are sampled, not allocation limits.",
        "Global Ion/GPU allocations may overlap PSS and must not be added to it.",
        "All-device trial has aggregate CPU and memory, not per-device attribution."
    ], "runs": runs}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("output")
    args = parser.parse_args()
    result = summarize(args.source)
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for run in result["runs"]:
        r = run["result"] or {}
        print(run["startUptimeMs"], r.get("mode"), r.get("devices"), "success", run["success"],
              "wall/CPU ms", r.get("wallMs"), r.get("cpuMs"), "peak PSS MiB",
              round((run["peak_pssKiB"] or 0) / 1024, 1))
        for b in run.get("benchmarks", []):
            print(" ", b["devices"], b["n_prompt"], b["n_gen"], "tok/s", b["avg_ts"])
