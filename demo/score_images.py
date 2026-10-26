# -*- coding: utf-8 -*-
"""Score extracted course images: which ones yield a clean, peak-rich curve.

Usage: python score_images.py <media_dir>
"""
import os
import sys
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "analysis-python"))

import cv2  # noqa: E402
import spectrum  # noqa: E402


def main():
    media = sys.argv[1]
    rows = []
    for deck in sorted(os.listdir(media)):
        d = os.path.join(media, deck)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            p = os.path.join(d, name)
            try:
                img = spectrum.imread_unicode(p)
            except Exception:
                continue
            h, w = img.shape[:2]
            if w < 220 or h < 160 or w > 1400 or h > 1000:
                continue
            try:
                res = spectrum.analyze_image(p, max_curves=1, mode="dark",
                                             smooth_win=9, baseline=True,
                                             min_height=0.12, min_dist=12)
                c = res["curves"][0]
                rows.append((len(c["peaks"]), c["pointCount"], w, h, deck, name))
            except Exception:
                continue
    rows.sort(reverse=True)
    print(f"total candidates: {len(rows)}")
    print("peaks  points   WxH        deck / file")
    for pk, pc, w, h, deck, name in rows[:25]:
        print(f"{pk:5d} {pc:7d}  {w}x{h}   {deck}/{name}")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()