# -*- coding: utf-8 -*-
"""Extract embedded media images from the course pptx files.

Usage: python extract_media.py <src_dir> <out_dir>
Each pptx is a zip; we copy ppt/media/* into out_dir/<slide_deck_name>/.
"""
import sys
import os
import zipfile


def main():
    src_dir = sys.argv[1]
    out_dir = sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    count = 0
    for name in sorted(os.listdir(src_dir)):
        if not name.lower().endswith(".pptx"):
            continue
        deck = os.path.splitext(name)[0]
        dst = os.path.join(out_dir, deck)
        os.makedirs(dst, exist_ok=True)
        try:
            with zipfile.ZipFile(os.path.join(src_dir, name)) as z:
                for info in z.namelist():
                    if "/media/" in info and not info.endswith("/"):
                        base = os.path.basename(info)
                        with z.open(info) as f, open(os.path.join(dst, base), "wb") as o:
                            o.write(f.read())
                        count += 1
        except zipfile.BadZipFile:
            print("skip (not a zip):", name)
    print("extracted files:", count)


if __name__ == "__main__":
    main()