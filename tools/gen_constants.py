#!/usr/bin/env python3
"""
从 docs/constants.json 生成两端的协议常量文件。

    python3 tools/gen_constants.py           # 写文件
    python3 tools/gen_constants.py --check   # 只比对，不一致就退出 1

在这之前，两端的常量是靠注释互指来同步的 ——「两边必须一致，否则…」写了四五处。
注释顶得住一时，顶不住半年，而且不一致的表现是「同一个网络里两台设备行为不同」，
排查时第一反应会是网络问题，不会想到常量。

两个仓库是独立的（见 README），所以：
  - 真源和生成器放在 AndroidFileManagerUtils（协议规范本来就在这里）；
  - **生成结果两边都提交**，于是各自单独 clone 也能构建，不需要另一个仓库在场；
  - Linux 端的路径按同级目录推断，也可以用 --linux 指定。

生成的文件带 DO-NOT-EDIT 头。真要改值，改 docs/constants.json 再跑一次。
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ANDROID_ROOT = HERE.parent
SOURCE = ANDROID_ROOT / "docs" / "constants.json"

DEFAULT_KOTLIN_OUT = (
    ANDROID_ROOT / "app/src/main/java/com/aynux/afmu/core/ProtocolConstants.kt"
)
# 同级目录约定；单独 clone 时用 --linux 指过去，或者干脆跳过（生成结果已提交）
DEFAULT_CPP_OUT = ANDROID_ROOT.parent / "afmu-linux" / "src" / "ProtocolConstants.h"

BANNER = "本文件由 {rel} 生成，**不要手改**。\n改值请编辑 docs/constants.json，然后跑 tools/gen_constants.py。"


def screaming(name: str) -> str:
    """camelCase → SCREAMING_SNAKE_CASE"""
    out = []
    for ch in name:
        if ch.isupper():
            out.append("_")
        out.append(ch.upper())
    return "".join(out)


def pascal(name: str) -> str:
    return name[0].upper() + name[1:]


def esc(value: str, lang: str) -> str:
    """字符串字面量转义。probePayload 和 ticketDomain 里都有真实换行。"""
    out = value.replace("\\", "\\\\").replace('"', '\\"')
    out = out.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    if lang == "cpp":
        # C++ 里 ?? 开头的三字符组虽然 C++17 已废除，转义掉更省心
        out = out.replace("?", "\\?")
    return out


def wrap_doc(doc: str, prefix: str, width: int = 96) -> list[str]:
    """按宽度折行。中文没有空格可断，所以按字符数切。"""
    if not doc:
        return []
    lines, cur = [], ""
    for word in doc.split(" "):
        # 中文长句没有空格，超长的单「词」直接按字符切
        while len(word) > width:
            lines.append(prefix + word[:width])
            word = word[width:]
        if len(cur) + len(word) + 1 > width:
            lines.append(prefix + cur)
            cur = word
        else:
            cur = f"{cur} {word}".strip()
    if cur:
        lines.append(prefix + cur)
    return lines


def render_kotlin(data: dict, rel: str) -> str:
    out: list[str] = ["package com.aynux.afmu.core", ""]
    out.append("/**")
    for line in BANNER.format(rel=rel).split("\n"):
        out.append(f" * {line}")
    out.append(" *")
    out.append(" * 这些值必须和 Linux 端的 ProtocolConstants.h 逐字一致 —— 它们是同一份 JSON 生成的。")
    out.append(" */")
    out.append("object ProtocolConstants {")
    for group in data["groups"]:
        out.append("")
        out.append(f"    // ---- {group['name']} " + "-" * max(0, 60 - len(group["name"])))
        out.extend(wrap_doc(group["doc"], "    // "))
        for c in group["constants"]:
            out.append("")
            if len(c["doc"]) <= 88:
                out.append(f"    /** {c['doc']} */")
            else:
                out.append("    /**")
                out.extend(wrap_doc(c["doc"], "     * "))
                out.append("     */")
            name = screaming(c["name"])
            value = c["value"]
            literal = f'"{esc(value, "kotlin")}"' if isinstance(value, str) else str(value)
            out.append(f"    const val {name} = {literal}")
    out.append("}")
    return "\n".join(out) + "\n"


def render_cpp(data: dict, rel: str) -> str:
    out: list[str] = ["#pragma once", ""]
    out.append("/*")
    for line in BANNER.format(rel=rel).split("\n"):
        out.append(f" * {line}")
    out.append(" *")
    out.append(" * 这些值必须和 Android 端的 ProtocolConstants.kt 逐字一致 —— 它们是同一份 JSON 生成的。")
    out.append(" */")
    out.append("")
    out.append("namespace afmu {")
    for group in data["groups"]:
        out.append("")
        out.append(f"// ---- {group['name']} " + "-" * max(0, 64 - len(group["name"])))
        out.extend(wrap_doc(group["doc"], "// "))
        for c in group["constants"]:
            out.append("")
            if len(c["doc"]) <= 88:
                out.append(f"/** {c['doc']} */")
            else:
                out.append("/**")
                out.extend(wrap_doc(c["doc"], " * "))
                out.append(" */")
            name = "k" + pascal(c["name"])
            value = c["value"]
            if isinstance(value, str):
                out.append(f'inline const char *const {name} = "{esc(value, "cpp")}";')
            else:
                out.append(f"inline constexpr int {name} = {value};")
    out.append("")
    out.append("} // namespace afmu")
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description="从 docs/constants.json 生成两端的协议常量")
    ap.add_argument("--check", action="store_true", help="只比对，不写文件；不一致退出 1")
    ap.add_argument("--kotlin", type=pathlib.Path, default=DEFAULT_KOTLIN_OUT)
    ap.add_argument("--linux", type=pathlib.Path, default=DEFAULT_CPP_OUT)
    args = ap.parse_args()

    data = json.loads(SOURCE.read_text(encoding="utf-8"))

    targets = [
        (args.kotlin, render_kotlin(data, "docs/constants.json")),
        (args.linux, render_cpp(data, "AndroidFileManagerUtils/docs/constants.json")),
    ]

    drift = False
    for path, content in targets:
        if not path.parent.is_dir():
            print(f"跳过 {path} —— 目录不存在（单独 clone 时正常）")
            continue
        current = path.read_text(encoding="utf-8") if path.exists() else None
        if current == content:
            print(f"一致 {path}")
            continue
        if args.check:
            drift = True
            print(f"不一致 {path} —— 跑一次 tools/gen_constants.py 重新生成", file=sys.stderr)
        else:
            path.write_text(content, encoding="utf-8")
            print(f"已写入 {path}")

    if drift:
        print("\n生成结果和 docs/constants.json 对不上了。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
