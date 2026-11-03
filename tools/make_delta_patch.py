#!/usr/bin/env python3
"""Build an ARASDLT1 delta patch from an installed APK to a new APK.

Usage:
    pip install bsdiff4
    python3 tools/make_delta_patch.py OLD.apk NEW.apk OUT.patch

The patch is a bsdiff delta whose three streams are recompressed with zlib, so
the app can apply it with java.util.zip and without shipping a bzip2 codec.
Format: magic "ARASDLT1", then little-endian u64 ctrlLen, diffLen, extraLen,
newSize, oldSize, followed by the three zlib streams (control triples, diff
bytes, extra bytes). Control triples keep bsdiff's signed-magnitude encoding.
"""

import bz2
import sys
import zlib

MAGIC = b"ARASDLT1"
BSDIFF_MAGIC = b"BSDIFF40"
HEADER_SIZE = 48


def decode_offset(raw: bytes) -> int:
    value = int.from_bytes(raw, "little")
    negative = bool(value & (1 << 63))
    value &= (1 << 63) - 1
    return -value if negative else value


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    old_path, new_path, out_path = sys.argv[1:]

    try:
        import bsdiff4
    except ImportError:
        print("bsdiff4 is missing: pip install bsdiff4", file=sys.stderr)
        return 2

    with open(old_path, "rb") as f:
        old = f.read()
    with open(new_path, "rb") as f:
        new = f.read()

    patch = bsdiff4.diff(old, new)
    if patch[:8] != BSDIFF_MAGIC:
        print("bsdiff4 returned an unexpected patch format", file=sys.stderr)
        return 1

    ctrl_len = decode_offset(patch[8:16])
    diff_len = decode_offset(patch[16:24])
    new_size = decode_offset(patch[24:32])
    if new_size != len(new):
        print(f"new size mismatch: patch says {new_size}, file is {len(new)}", file=sys.stderr)
        return 1

    offset = 32
    segments = []
    for length in (ctrl_len, diff_len, None):
        end = len(patch) if length is None else offset + length
        segments.append(bz2.decompress(patch[offset:end]))
        offset = end
    ctrl_raw, diff_raw, extra_raw = segments

    ctrl = zlib.compress(ctrl_raw, 9)
    diff = zlib.compress(diff_raw, 9)
    extra = zlib.compress(extra_raw, 9)

    with open(out_path, "wb") as f:
        f.write(MAGIC)
        f.write(len(ctrl).to_bytes(8, "little"))
        f.write(len(diff).to_bytes(8, "little"))
        f.write(len(extra).to_bytes(8, "little"))
        f.write(len(new).to_bytes(8, "little"))
        f.write(len(old).to_bytes(8, "little"))
        f.write(ctrl)
        f.write(diff)
        f.write(extra)

    print(
        f"old {len(old)} bytes, new {len(new)} bytes, "
        f"bsdiff {len(patch)} bytes, delta {HEADER_SIZE + len(ctrl) + len(diff) + len(extra)} bytes"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
