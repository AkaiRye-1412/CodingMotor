# -*- coding: utf-8 -*-
"""把 packaging/windows/icons/icon-*.png 拼成 Windows ICO（PNG-in-ICO，Vista+ 规范）。"""
import os
import struct

ICONS = r"C:\Users\Administrator\Desktop\MotorSizer(1)\MotorSizer\packaging\windows\icons"
OUT = os.path.join(ICONS, "motorsizer.ico")

SIZES = [16, 24, 32, 48, 64, 128, 256]

entries = []
images = []
offset = 6 + 16 * len(SIZES)  # header(6) + N * entry(16)

for size in SIZES:
    path = os.path.join(ICONS, f"icon-{size}.png")
    data = open(path, "rb").read()
    width_byte = 0 if size >= 256 else size
    entries.append(
        struct.pack(
            "<BBBBHHII",
            width_byte,   # width (0 = 256)
            width_byte,   # height (0 = 256)
            0,            # color count
            0,            # reserved
            1,            # color planes
            32,           # bits per pixel
            len(data),    # bytes in resource
            offset,       # image offset
        )
    )
    images.append(data)
    offset += len(data)

with open(OUT, "wb") as fh:
    fh.write(struct.pack("<HHH", 0, 1, len(SIZES)))  # reserved, type=1(icon), count
    for entry in entries:
        fh.write(entry)
    for data in images:
        fh.write(data)

raw = open(OUT, "rb").read()
print("ICO written:", OUT, len(raw), "bytes, images:", len(SIZES))
assert raw[:4] == b"\x00\x00\x01\x00"
print("OK")
