"""从化学手册/教材 PDF 提取全文与目录，产出 ChemLab 知识库资源。

产出（写入 app-java/resources/kb/）：
    <book>.txt     每本书全文；页与页之间用换页符 \f 分隔，第 n 页 = split[n-1]
    <book>.toc.json  该书的书签目录 [[level, title, page], ...]

用法：
    python extract_kb.py <bookId> <title> <author> <pdf> [...]
"""
from __future__ import annotations

import json
import os
import sys

import pymupdf


def extract(book_id: str, title: str, author: str, pdf: str, out_dir: str) -> dict:
    doc = pymupdf.open(pdf)
    toc = doc.get_toc()  # [[level, title, page], ...]，page 为 1 基
    parts = []
    total_chars = 0
    for i in range(len(doc)):
        try:
            t = doc[i].get_text("text")
        except Exception as e:  # 单页失败不影响整体
            t = ""
            print(f"  [warn] 第 {i+1} 页提取失败：{e}", file=sys.stderr)
        parts.append(t)
        total_chars += len(t)

    # 页间以换页符分隔；正文中的孤立 \f 也统一规范化，避免错位
    full = "\f".join(p.replace("\f", " ") for p in parts)
    with open(os.path.join(out_dir, book_id + ".txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(full)

    toc_out = [[int(l), str(t).strip(), int(p)] for l, t, p in toc]
    with open(os.path.join(out_dir, book_id + ".toc.json"), "w", encoding="utf-8") as f:
        json.dump(toc_out, f, ensure_ascii=False)

    info = {
        "id": book_id, "title": title, "author": author,
        "pages": len(doc), "chars": total_chars, "toc": len(toc_out),
    }
    doc.close()
    return info


def main(argv):
    if len(argv) < 5:
        print(__doc__)
        return 1
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "app-java", "resources", "kb")
    out_dir = os.path.normpath(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    args = argv[1:]
    infos = []
    for i in range(0, len(args), 4):
        book_id, title, author, pdf = args[i:i + 4]
        print(f"提取 {book_id}: {os.path.basename(pdf)} ...")
        infos.append(extract(book_id, title, author, pdf, out_dir))
        print(f"  完成：{infos[-1]}")

    with open(os.path.join(out_dir, "books.json"), "w", encoding="utf-8") as f:
        json.dump(infos, f, ensure_ascii=False, indent=2)
    print("全部完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))