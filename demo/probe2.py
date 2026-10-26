# -*- coding: utf-8 -*-
"""Probe top-ranked images for a clean, presentable spectrum extraction."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "analysis-python"))

import spectrum  # noqa: E402

MEDIA = os.path.join(HERE, "media")

CASES = [
    "第四章 酸碱滴定1-48(1)/image38.jpeg",
    "第四章酸碱滴定2-48/image59.png",
    "第四章 酸碱滴定1-48(1)/image43.png",
    "第四章酸碱滴定3-48/image49.png",
    "第二章 数据及误差的处理-48(1)/image50.png",
    "第二章 数据及误差的处理-48(1)/image51.png",
    "第二章 数据及误差的处理-48(1)/image52.png",
    "第二章 数据及误差的处理-48(1)/image15.png",
    "第四章酸碱滴定2-48/image74.png",
]

for rel in CASES:
    p = os.path.join(MEDIA, rel)
    try:
        res = spectrum.analyze_image(p, max_curves=1, mode="dark",
                                     smooth_win=9, baseline=True,
                                     min_height=0.15, min_dist=18)
        c = res["curves"][0]
        heights = [round(pk["height"], 3) for pk in c["peaks"]]
        print(f"{rel}")
        print(f"   size {res['imageWidth']}x{res['imageHeight']} roi={res['plotArea']}")
        print(f"   points={c['pointCount']} peaks={len(c['peaks'])} area={round(c['area'],2)}")
        print(f"   heights={heights}")
    except Exception as e:
        print(f"{rel}  FAILED: {e}")