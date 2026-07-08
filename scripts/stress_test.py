#!/usr/bin/env python3
"""
Mini-SSP 压力测试脚本
并发向 /api/v1/bid 发请求，统计吞吐量、延迟分布(P50/P95/P99)、错误率。

用法：
  python3 scripts/stress_test.py                        # 默认 50 并发，60 秒
  python3 scripts/stress_test.py --concurrency 100 --duration 30
  python3 scripts/stress_test.py --scenario "Full Stack(cache+kafka)"
"""

import argparse
import csv
import time
import uuid
import json
import statistics
import urllib.request
import urllib.error
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock

# ---------- 默认配置 ----------
DEFAULT_URL         = "http://localhost:8080/api/v1/bid"
DEFAULT_SLOT_ID     = "slot-test-001"
DEFAULT_CONCURRENCY = 50      # 并发线程数
DEFAULT_DURATION    = 60      # 压测持续秒数
# ------------------------------

# 全局统计（多线程写，用 Lock 保护）
latencies   = []   # 每个成功请求的耗时（ms）
results     = {"fill": 0, "no_fill": 0, "error": 0}
stats_lock  = Lock()


def send_one(url: str, slot_id: str) -> tuple[str, float]:
    """
    发一个竞价请求，返回 (result, elapsed_ms)。
    result 是 "fill" / "no_fill" / "error"。
    """
    payload = json.dumps({
        "id": str(uuid.uuid4()),   # 每次随机，避免命中 bid_result 缓存影响结果
        "tagid": slot_id,
        "device": {"os": "iOS"}
    }).encode()

    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            elapsed = (time.perf_counter() - t0) * 1000
            body = json.loads(resp.read())
            result = "no_fill" if body.get("code") == 1 else "fill"
            return result, elapsed
    except Exception:
        elapsed = (time.perf_counter() - t0) * 1000
        return "error", elapsed


def worker(url: str, slot_id: str, stop_at: float):
    """
    单个工作线程：不断发请求直到 stop_at 时间戳到达。
    """
    while time.time() < stop_at:
        result, elapsed = send_one(url, slot_id)
        with stats_lock:
            results[result] += 1
            if result != "error":
                latencies.append(elapsed)


def percentile(data: list[float], p: float) -> float:
    """计算百分位数"""
    if not data:
        return 0.0
    sorted_data = sorted(data)
    idx = int(len(sorted_data) * p / 100)
    idx = min(idx, len(sorted_data) - 1)
    return sorted_data[idx]


def build_report(scenario: str, concurrency: int, duration: int, elapsed: float) -> dict:
    total    = sum(results.values())
    fill     = results["fill"]
    no_fill  = results["no_fill"]
    errors   = results["error"]
    qps      = total / elapsed if elapsed else 0.0

    return {
        "scenario": scenario,
        "concurrency": concurrency,
        "duration_s": duration,
        "elapsed_s": elapsed,
        "total": total,
        "qps": qps,
        "fill": fill,
        "fill_rate": fill / total * 100 if total else 0.0,
        "no_fill": no_fill,
        "no_fill_rate": no_fill / total * 100 if total else 0.0,
        "error": errors,
        "error_rate": errors / total * 100 if total else 0.0,
        "p50_ms": percentile(latencies, 50) if latencies else 0.0,
        "p95_ms": percentile(latencies, 95) if latencies else 0.0,
        "p99_ms": percentile(latencies, 99) if latencies else 0.0,
        "max_ms": max(latencies) if latencies else 0.0,
        "avg_ms": statistics.mean(latencies) if latencies else 0.0,
    }


def print_report(report: dict):
    total    = report["total"]
    fill     = report["fill"]
    no_fill  = report["no_fill"]
    errors   = report["error"]

    print()
    print("=" * 52)
    print(f"  场景：{report['scenario']}")
    print(f"  并发：{report['concurrency']}  持续：{report['duration_s']}s  实际耗时：{report['elapsed_s']:.1f}s")
    print("=" * 52)
    print(f"  总请求数   : {total}")
    print(f"  吞吐量     : {report['qps']:.1f} req/s")
    print(f"  fill       : {fill}  ({report['fill_rate']:.1f}%)" if total else "  fill: 0")
    print(f"  no_fill    : {no_fill}  ({report['no_fill_rate']:.1f}%)" if total else "  no_fill: 0")
    print(f"  error      : {errors}  ({report['error_rate']:.1f}%)" if total else "  error: 0")
    if report["p50_ms"]:
        print(f"  P50 延迟   : {report['p50_ms']:.1f} ms")
        print(f"  P95 延迟   : {report['p95_ms']:.1f} ms")
        print(f"  P99 延迟   : {report['p99_ms']:.1f} ms")
        print(f"  最大延迟   : {report['max_ms']:.1f} ms")
        print(f"  平均延迟   : {report['avg_ms']:.1f} ms")
    print("=" * 52)
    print()


def append_csv(path: str, report: dict):
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    write_header = not output.exists()
    with output.open("a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(report.keys()))
        if write_header:
            writer.writeheader()
        writer.writerow(report)


def run(url: str, slot_id: str, concurrency: int, duration: int, scenario: str):
    print(f"\n开始压测：{scenario}")
    print(f"目标：{url}  并发：{concurrency}  持续：{duration}s")
    print("压测中，请稍候...\n")

    stop_at = time.time() + duration
    t0 = time.time()

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(worker, url, slot_id, stop_at) for _ in range(concurrency)]
        for f in as_completed(futures):
            f.result()   # 等所有 worker 结束，传播异常（如有）

    elapsed = time.time() - t0
    report = build_report(scenario, concurrency, duration, elapsed)
    print_report(report)
    return report


def main():
    parser = argparse.ArgumentParser(description="Mini-SSP 压力测试")
    parser.add_argument("--url",         default=DEFAULT_URL)
    parser.add_argument("--slot",        default=DEFAULT_SLOT_ID)
    parser.add_argument("--concurrency", type=int, default=DEFAULT_CONCURRENCY)
    parser.add_argument("--duration",    type=int, default=DEFAULT_DURATION)
    parser.add_argument("--scenario",    default="压测")
    parser.add_argument("--csv-output",  help="把本次压测结果追加写入 CSV 文件")
    args = parser.parse_args()

    try:
        report = run(args.url, args.slot, args.concurrency, args.duration, args.scenario)
        if args.csv_output:
            append_csv(args.csv_output, report)
    except KeyboardInterrupt:
        print("\n压测被中断，输出当前统计：")
        report = build_report(args.scenario, args.concurrency, args.duration, DEFAULT_DURATION)
        print_report(report)
        if args.csv_output:
            append_csv(args.csv_output, report)


if __name__ == "__main__":
    main()
