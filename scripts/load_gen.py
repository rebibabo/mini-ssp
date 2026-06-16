#!/usr/bin/env python3
"""
Mini-SSP 流量生成器
在爆发期和冷却期之间随机切换，模拟真实广告流量的波动。

用法：
  python3 scripts/load_gen.py
  Ctrl+C 优雅停止并打印统计
"""

import random
import time
import uuid
import urllib.request
import urllib.error
import json
from concurrent.futures import ThreadPoolExecutor, as_completed

# ---------- 配置 ----------
SSP_URL = "http://localhost:8080/api/v1/bid"

# 广告位：70% fill，30% no_fill
SLOTS = [
    ("slot-test-001", 0.7),   # 有 DSP 关联，能 fill
    ("slot-no-dsp",   0.3),   # 无 DSP，必 no_fill
]

# 爆发期参数
BURST_CONCURRENCY = 15        # 最多同时发多少个请求
BURST_COUNT_RANGE  = (20, 50) # 每次爆发总共发多少个请求
BURST_DURATION_RANGE = (3, 8) # 爆发持续几秒（控制发送间隔）

# 冷却期参数
COOL_INTERVAL_RANGE = (0.5, 2.0)  # 每个请求之间等多久（秒）
COOL_DURATION_RANGE = (5, 15)     # 冷却持续几秒
# --------------------------

# 统计
total = 0
fill_count = 0
nofill_count = 0
error_count = 0


def pick_slot():
    """按权重随机选广告位"""
    slots, weights = zip(*SLOTS)
    return random.choices(slots, weights=weights, k=1)[0]


def send_request():
    """发一个竞价请求，返回 'fill' / 'no_fill' / 'error'"""
    payload = json.dumps({
        "requestId": str(uuid.uuid4()),
        "adSlotId": pick_slot(),
        "device": {"os": random.choice(["iOS", "Android"])}
    }).encode()

    req = urllib.request.Request(
        SSP_URL,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=2) as resp:
            body = json.loads(resp.read())
            return "no_fill" if body.get("code") == 1 else "fill"
    except Exception:
        return "error"


def record(result):
    global total, fill_count, nofill_count, error_count
    total += 1
    if result == "fill":
        fill_count += 1
    elif result == "no_fill":
        nofill_count += 1
    else:
        error_count += 1


def burst_phase():
    """爆发期：用线程池并发发一批请求"""
    count = random.randint(*BURST_COUNT_RANGE)
    duration = random.uniform(*BURST_DURATION_RANGE)
    interval = duration / count   # 平均间隔，让请求分散在整个爆发期里

    print(f"  [爆发] 发 {count} 个请求，持续约 {duration:.1f}s")

    with ThreadPoolExecutor(max_workers=BURST_CONCURRENCY) as pool:
        futures = []
        for _ in range(count):
            futures.append(pool.submit(send_request))
            time.sleep(interval)   # 分散提交，不全堆在一瞬间
        for f in as_completed(futures):
            record(f.result())


def cool_phase():
    """冷却期：低频逐个发请求"""
    duration = random.uniform(*COOL_DURATION_RANGE)
    deadline = time.time() + duration

    print(f"  [冷却] 持续约 {duration:.1f}s，低频发送")

    while time.time() < deadline:
        record(send_request())
        time.sleep(random.uniform(*COOL_INTERVAL_RANGE))


def print_stats():
    print(f"\n{'='*45}")
    print(f"  总请求：{total}")
    print(f"  fill：  {fill_count}  ({fill_count/total*100:.1f}%)" if total else "  fill：0")
    print(f"  no_fill：{nofill_count}  ({nofill_count/total*100:.1f}%)" if total else "  no_fill：0")
    print(f"  error：  {error_count}")
    print(f"{'='*45}")


def run():
    print("Mini-SSP 流量生成器启动，Ctrl+C 停止\n")
    cycle = 0
    while True:
        cycle += 1
        # 随机决定这个周期是爆发还是冷却（各 50%）
        phase = random.choice(["burst", "cool"])
        print(f"[周期 {cycle}] {'🔥 爆发期' if phase == 'burst' else '❄️  冷却期'} | 已发 {total} 个请求")
        if phase == "burst":
            burst_phase()
        else:
            cool_phase()


if __name__ == "__main__":
    try:
        run()
    except KeyboardInterrupt:
        print_stats()
        print("已停止。")
