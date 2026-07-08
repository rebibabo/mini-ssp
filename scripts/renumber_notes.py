#!/usr/bin/env python3
"""
Renumber docs/notes markdown files.

- Keeps each file's H1 title unchanged.
- Renumbers H2 headings in each topic file from 1.
- Skips headings inside fenced code blocks.
- Refreshes docs/notes/README.md with section counts.
"""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
NOTES_DIR = ROOT / "docs" / "notes"
README = NOTES_DIR / "README.md"

TOPIC_FILES = [
    "00-progress.md",
    "01-spring-java-basics.md",
    "02-auction-core.md",
    "03-concurrency-threading.md",
    "04-cache-rate-limit.md",
    "05-testing.md",
    "06-observability.md",
    "07-bid-log-kafka.md",
    "08-tooling-api-docs.md",
    "09-performance-benchmark.md",
    "10-pitfalls.md",
]


def strip_existing_number(title: str) -> str:
    return re.sub(r"^\d+\.\s+", "", title).strip()


def renumber_file(path: Path) -> int:
    lines = path.read_text(encoding="utf-8").splitlines()
    output = []
    section_no = 0
    in_fence = False

    for line in lines:
        if line.startswith("```"):
            in_fence = not in_fence
            output.append(line)
            continue

        if not in_fence and line.startswith("> 本文件为分类整理后的主题笔记"):
            output.append("> 本文件为分类整理后的主题笔记。")
            continue

        if not in_fence:
            match = re.match(r"^##\s+(.+?)\s*$", line)
            if match:
                section_no += 1
                title = strip_existing_number(match.group(1))
                output.append(f"## {section_no}. {title}")
                continue

        output.append(line)

    path.write_text("\n".join(output).rstrip() + "\n", encoding="utf-8")
    return section_no


def read_h1(path: Path) -> str:
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return path.stem


def refresh_readme(section_counts: dict[str, int]) -> None:
    rows = []
    for filename in TOPIC_FILES:
        path = NOTES_DIR / filename
        title = read_h1(path)
        rows.append(f"| [{filename}](./{filename}) | {title} | {section_counts[filename]} |")

    content = [
        "# Mini-SSP 学习笔记索引",
        "",
        "> 后续直接维护这里的分类笔记；如需重排标题编号，运行 `python3 scripts/renumber_notes.py`。",
        "",
        "| 文件 | 主题 | 小节数 |",
        "|---|---|---:|",
        *rows,
        "",
        "## 推荐阅读路径",
        "",
        "1. 先看 `00-progress.md` 了解项目演进。",
        "2. 再看 `02-auction-core.md` 把竞价主流程串起来。",
        "3. 性能相关优先看 `03-concurrency-threading.md`、`07-bid-log-kafka.md`、`09-performance-benchmark.md`。",
        "4. 面试复盘可重点看 `06-observability.md` 和 `10-pitfalls.md`。",
        "",
    ]
    README.write_text("\n".join(content), encoding="utf-8")


def main() -> None:
    section_counts = {}
    for filename in TOPIC_FILES:
        path = NOTES_DIR / filename
        if not path.exists():
            raise FileNotFoundError(path)
        section_counts[filename] = renumber_file(path)
    refresh_readme(section_counts)


if __name__ == "__main__":
    main()
