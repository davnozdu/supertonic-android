#!/usr/bin/env python3
"""Summarize the latest invocation of each backend from the probe JSONL."""
import argparse
import json
from pathlib import Path


def summarize(path):
    invocations = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if not row.get("benchmark") or not row.get("backend"):
            continue
        key = (row["backend"], row.get("threads"))
        if row["stage"] == "before_load":
            invocations[key] = [row]
        elif key in invocations:
            invocations[key].append(row)
    summaries = []
    for (backend, threads), rows in invocations.items():
        baseline = rows[0]["details"]
        samples = [r["details"] for r in rows if r["stage"] == "memory"]
        generations = []
        for row in rows:
            if row["stage"] != "generated":
                continue
            details = dict(row["details"])
            wall = details["wallMs"]
            details["cpuBusyPercentOfEightCores"] = round(details["cpuMs"] / wall * 100 / 8, 2)
            details["endToEndTokensPerSecond"] = round(details["tokens"] * 1000 / wall, 2) if details.get("tokens") else None
            details["outputCharacters"] = len(details.get("text", ""))
            generations.append(details)
        summary = {
            "backend": backend, "threads": threads,
            "startUptimeMs": rows[0]["uptimeMs"],
            "success": any(r["stage"] == "complete" for r in rows)
                and not any(r["stage"] in ("failed", "budget_stop", "cancelled") for r in rows),
            "baseline": baseline,
            "loaded": next((r["details"] for r in rows if r["stage"] == "loaded"), None),
            "samples": len(samples),
            "generations": generations,
            "failures": [r for r in rows if r["stage"] in ("failed", "budget_stop", "cancelled")],
            "afterUnload": next((r["details"] for r in reversed(rows) if r["stage"] == "after_unload"), None),
        }
        for field in ("pssKiB", "rssKiB", "vmSwapKiB", "ionUsedKiB", "gpuUsedKiB"):
            values = [s[field] for s in samples if s.get(field) is not None]
            summary["peak_" + field] = max(values) if values else None
        available = [s["memAvailableKiB"] for s in samples]
        summary["min_memAvailableKiB"] = min(available) if available else None
        summaries.append(summary)
    return {
        "source": str(path),
        "notes": ["CPU busy percentage is not power or energy.",
                  "Global GPU/Ion may overlap process PSS; do not add them.",
                  "Formats, quantization and chat templates differ between runtimes.",
                  "End-to-end throughput includes prefill and conversation setup."],
        "runs": summaries,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("output")
    args = parser.parse_args()
    result = summarize(args.source)
    Path(args.output).write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    for run in result["runs"]:
        print(run["backend"], run["threads"], "success=" + str(run["success"]),
              "PSS_MiB=" + str(round((run["peak_pssKiB"] or 0) / 1024, 1)),
              "available_MiB=" + str(round((run["min_memAvailableKiB"] or 0) / 1024, 1)))
        for generation in run["generations"]:
            print(" ", generation["case"], generation["round"], "wall_ms=" + str(generation["wallMs"]),
                  "cpu_ms=" + str(generation["cpuMs"]), "tokens=" + str(generation.get("tokens")),
                  "e2e_tps=" + str(generation["endToEndTokensPerSecond"]))
