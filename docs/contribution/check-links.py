#!/usr/bin/env python3
"""Проверка относительных ссылок и якорей в markdown-документации репозитория.

Запуск из корня репозитория: python docs/contribution/check-links.py

Проверяет ссылки вида [текст](path#anchor) в README.md, AGENTS.md, CLAUDE.md,
CHANGELOG.md и docs/**/*.md (кроме docs/generated):
- цель относительного пути существует;
- якорь разрешается: явный <a id="..."> либо заголовок в стиле GitHub
  (нижний регистр, пробелы -> дефисы, пунктуация удаляется, повторяющиеся
  заголовки получают суффиксы -1, -2, ...).
Внешние ссылки (http/https/mailto) не проверяются. Выходной код 1 — есть битые.
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
SCAN = ["README.md", "AGENTS.md", "CLAUDE.md", "CHANGELOG.md", "docs"]
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")


def slug(heading: str) -> str:
    text = heading.strip().lower()
    text = "".join(c for c in text if c.isalnum() or c.isspace() or c == "-")
    return re.sub(r"\s+", "-", text).strip("-")


def anchors_of(path: str) -> set:
    ids, seen = set(), {}
    for line in open(path, encoding="utf-8"):
        for m in re.finditer(r'<a\s[^>]*\bid="([^"]+)"', line):
            ids.add(m.group(1))
        h = re.match(r"^#{1,6}\s+(.+?)\s*$", line.rstrip())
        if h:
            s = slug(h.group(1))
            if s in seen:
                seen[s] += 1
                ids.add(f"{s}-{seen[s]}")
            else:
                seen[s] = 0
                ids.add(s)
    return ids


def main() -> int:
    files = []
    for entry in SCAN:
        p = os.path.join(ROOT, entry)
        if os.path.isfile(p):
            files.append(p)
        elif os.path.isdir(p):
            for dp, _, fns in os.walk(p):
                if os.path.relpath(dp, ROOT).replace("\\", "/") == "docs/generated":
                    continue
                files += [os.path.join(dp, f) for f in fns if f.endswith(".md")]

    broken, checked = [], 0
    for f in files:
        base = os.path.dirname(f)
        own = anchors_of(f)
        for m in LINK.finditer(open(f, encoding="utf-8").read()):
            target = m.group(1).strip()
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            path, _, anchor = target.partition("#")
            checked += 1
            if not path:  # ссылка на раздел того же файла
                if anchor and anchor not in own:
                    broken.append(f"{os.path.relpath(f, ROOT)}: {target}")
                continue
            full = os.path.normpath(os.path.join(base, path))
            if not os.path.exists(full):
                broken.append(f"{os.path.relpath(f, ROOT)}: {target}")
            elif anchor and full.endswith(".md") and anchor not in anchors_of(full):
                broken.append(f"{os.path.relpath(f, ROOT)}: {target}")

    print(f"Проверено ссылок: {checked}; файлов: {len(files)}")
    if broken:
        print("Битые ссылки/якоря:")
        print("\n".join(broken))
        return 1
    print("Битых ссылок нет.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
