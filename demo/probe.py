# -*- coding: utf-8 -*-
"""Probe a few chosen images with different extraction settings."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "analysis-python"))

import spectrum  # noqa: E402

MEDIA = os.path.join(HERE, "media")

CASES = [
    ("第四章酸碱滴定3-48/image26.png", dict(max_curves=2, mode="color", min_height=0.25, min_dist=25)),
    ("第四章酸碱滴定3-48/image26.png", dict(max_curves=3, mode="color", min_height=0.25, min_dist=25)),
    ("第二章 数据及误差的处理-48(1)/image22.png", dict(max_curves=2, mode="color", min_height=0.20, min_dist=30)),
    ("第四章 酸碱滴定1-48(1)/image43.png", dict(max_curves=1, mode="dark", min_height=0.20, min_dist=25)),
]

for rel, kw in CASES:
    p = os.path.join(MEDIA, rel)
    try:
        res = spectrum.analyze_image(p, smooth_win=9, baseline=True, **kw)
        print(f"== {rel}  {kw}")
        print(f"   size {res['imageWidth']}x{res['imageHeight']}  roi={res['plotArea']}")
        for c in res["curves"]:
            print(f"   {c['label']}: points={c['pointCount']} peaks={len(c['peaks'])} "
                  f"area={c['area']} color={c['color']}")
    except Exception as e:
        print(f"== {rel}  FAILED: {e}")