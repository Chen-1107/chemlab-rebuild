"""构建 ChemLab 知识库统一索引 resources/kb/index.json。

结构：
    version, books:[{id,title,author,edition,pages,mode,file,chars,toc:[[lvl,title,page],...]}],
    ch:[{id,book,t,cat,p,lvl}],   # 各书一级章节（含 Dean's）
    e:[{p,c,s,t}]                 # Dean's 内联条目（原 kb.json）

数据来源：books.json（新书元数据）、*.toc.json（PDF 书签）、kb.json（Dean's 既有内容）。
"""
from __future__ import annotations

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
KB = os.path.normpath(os.path.join(HERE, "..", "app-java", "resources", "kb"))
LEGACY = os.path.normpath(os.path.join(HERE, "..", "app-java", "resources", "kb.json"))

# 每本书的学科分类标签（用于分类筛选）
BOOK_CAT = {
    "deans": "分析化学手册",
    "lange": "化学手册",
    "massspec": "质谱",
    "physchem": "物理化学",
}

# 各书一级章节的分类：整书归一类，细分时可按标题关键词覆盖
def classify(book_id: str, title: str) -> str:
    return BOOK_CAT.get(book_id, "其他")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def load_pages(book_id: str):
    p = os.path.join(KB, book_id + ".txt")
    if not os.path.isfile(p):
        return None
    return open(p, encoding="utf-8").read().split("\f")


def detect_lange_sections(pages, start_page=15):
    """检测正文中形如「1.5」后跟全大写标题的章节起始页，返回 [(sec, num, title, page)]。"""
    num_re = re.compile(r"^(\d{1,2})\.(\d{1,2})$")
    caps_re = re.compile(r"^[A-Z][A-Z0-9 ,&()'’/\-\.]{3,70}$")
    found, seen = [], set()
    for i, pg in enumerate(pages, 1):
        if i < start_page:
            continue
        lines = [l.strip() for l in pg.splitlines() if l.strip()]
        for k in range(min(4, len(lines))):
            m = num_re.match(lines[k])
            if not m:
                break
            nxt = lines[k + 1] if k + 1 < len(lines) else ""
            if (caps_re.match(nxt) and not nxt.startswith("TABLE")
                    and not nxt.startswith("FIGURE") and not nxt.startswith("SECTION")):
                key = (m.group(1), m.group(2))
                if key not in seen:
                    seen.add(key)
                    found.append((int(m.group(1)), int(m.group(2)), nxt, i))
            break
    return found


def build_lange(pages):
    """Lange's 无书签：用正文标题检测构造「4 个 Section + 各自小节」目录。"""
    sections = [
        (1, "SECTION 1  INORGANIC CHEMISTRY", 11),
        (2, "SECTION 2  ORGANIC CHEMISTRY", 430),
        (3, "SECTION 3  SPECTROSCOPY", 1242),
        (4, "SECTION 4  GENERAL INFORMATION AND CONVERSION TABLES", 1385),
    ]
    found = detect_lange_sections(pages)
    toc = []
    for sec, name, start in sections:
        toc.append([1, name, start])
        subs = sorted((num, title, page) for s, num, title, page in found if s == sec)
        for num, title, page in subs:
            toc.append([2, f"{sec}.{num}  {title}", page])
    return toc


def clean_toc(toc):
    """过滤封面/版权等噪声，只保留正文结构。"""
    noise = {"cover", "title page", "copyright page", "contents",
             "acknowledgments", "about the author", "preface", "index"}
    out = []
    for lvl, title, page in toc:
        t = title.strip()
        if t.lower() in noise:
            continue
        out.append([int(lvl), t, int(page)])
    return out


def main():
    books_meta = load(os.path.join(KB, "books.json"))
    legacy = load(LEGACY)

    books = []
    ch = []
    e = []

    # Dean's：沿用既有内容，作为 index 模式
    books.append({
        "id": "deans",
        "title": legacy.get("src", "Dean's Analytical Chemistry Handbook"),
        "author": legacy.get("author", ""),
        "edition": "2e",
        "pages": legacy.get("pages", 0),
        "mode": "index",
        "file": "",
        "chars": 0,
        "toc": [[1, c["t"], c["p"]] for c in legacy.get("ch", [])],
    })
    for c in legacy.get("ch", []):
        ch.append({"id": c["id"], "book": "deans", "t": c["t"],
                   "cat": c.get("cat", "其他"), "p": c["p"], "lvl": 1})
    e = legacy.get("e", [])

    # 新书：fulltext 模式
    for b in books_meta:
        bid = b["id"]
        toc_path = os.path.join(KB, bid + ".toc.json")
        pages = load_pages(bid)
        if bid == "lange":
            toc = build_lange(pages)
        else:
            toc = clean_toc(load(toc_path))
        books.append({
            "id": bid,
            "title": b["title"],
            "author": b["author"],
            "edition": "",
            "pages": b["pages"],
            "mode": "fulltext",
            "file": bid + ".txt",
            "chars": b["chars"],
            "toc": toc,
        })
        for lvl, title, page in toc:
            if lvl == 1:
                ch.append({"id": f"{bid}:{page}", "book": bid, "t": title,
                           "cat": classify(bid, title), "p": page, "lvl": 1})

    index = {"version": 2, "books": books, "ch": ch, "e": e}
    with open(os.path.join(KB, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)

    print("books:", [(b["id"], b["mode"], len(b["toc"])) for b in books])
    print("chapters:", len(ch), " entries:", len(e))
    print("index.json 写出完成")


if __name__ == "__main__":
    sys.exit(main())