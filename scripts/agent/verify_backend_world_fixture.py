#!/usr/bin/env python3
"""Reject backend comparisons that would finish generating terrain at startup."""

import argparse
from collections import Counter
from functools import lru_cache
import gzip
import json
import math
from pathlib import Path
import struct
import sys
import zlib


class NbtCursor:
    def __init__(self, payload):
        self.payload = memoryview(payload)
        self.offset = 0

    def read(self, size):
        result = self.payload[self.offset:self.offset + size]
        if len(result) != size:
            raise ValueError("truncated NBT payload")
        self.offset += size
        return result

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self):
        return self.read(self.number("H")).tobytes().decode("utf-8")

    def skip(self, kind):
        sizes = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
        if kind in sizes:
            self.read(sizes[kind])
        elif kind in (7, 11, 12):
            count = self.number("i")
            if count < 0:
                raise ValueError("negative NBT array length")
            self.read(count * {7: 1, 11: 4, 12: 8}[kind])
        elif kind == 8:
            self.string()
        elif kind == 9:
            child = self.number("B")
            count = self.number("i")
            if count < 0:
                raise ValueError("negative NBT list length")
            for _ in range(count):
                self.skip(child)
        elif kind == 10:
            while child := self.number("B"):
                self.string()
                self.skip(child)
        else:
            raise ValueError(f"unsupported NBT tag {kind}")


def chunk_status(payload):
    cursor = NbtCursor(payload)
    if cursor.number("B") != 10:
        raise ValueError("chunk root is not an NBT compound")
    cursor.string()
    while kind := cursor.number("B"):
        name = cursor.string()
        if name == "Status" and kind == 8:
            return cursor.string()
        cursor.skip(kind)
    raise ValueError("chunk has no Status tag")


@lru_cache(maxsize=16)
def region_bytes(path):
    return Path(path).read_bytes()


def status_at(world, chunk_x, chunk_z):
    region = world / "dimensions/minecraft/overworld/region" / f"r.{chunk_x // 32}.{chunk_z // 32}.mca"
    if not region.is_file():
        return "missing"
    content = region_bytes(str(region))
    slot = (chunk_x % 32) + 32 * (chunk_z % 32)
    sector = int.from_bytes(content[slot * 4:slot * 4 + 3], "big")
    if sector == 0:
        return "missing"
    offset = sector * 4096
    length = int.from_bytes(content[offset:offset + 4], "big")
    compression = content[offset + 4]
    compressed = content[offset + 5:offset + 4 + length]
    if compression == 1:
        payload = gzip.decompress(compressed)
    elif compression == 2:
        payload = zlib.decompress(compressed)
    elif compression == 3:
        payload = compressed
    else:
        raise ValueError(f"unsupported region compression {compression}")
    return chunk_status(payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", required=True, type=Path)
    parser.add_argument("--camera", required=True)
    parser.add_argument("--radius", type=int, default=14)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    camera = [float(value) for value in args.camera.split(",")]
    if len(camera) != 5 or args.radius < 0:
        parser.error("camera must be x,y,z,yaw,pitch and radius must be nonnegative")
    center_x, center_z = math.floor(camera[0] / 16), math.floor(camera[2] / 16)
    counts = Counter()
    incomplete = []
    for z in range(center_z - args.radius, center_z + args.radius + 1):
        for x in range(center_x - args.radius, center_x + args.radius + 1):
            try:
                status = status_at(args.world, x, z)
            except (OSError, ValueError, zlib.error) as error:
                status = f"invalid:{error}"
            counts[status] += 1
            if status != "minecraft:full":
                incomplete.append({"x": x, "z": z, "status": status})
    report = {
        "status": "passed" if not incomplete else "invalid-fixture",
        "world": str(args.world.resolve()),
        "cameraChunk": {"x": center_x, "z": center_z},
        "radius": args.radius,
        "chunkCount": (2 * args.radius + 1) ** 2,
        "statusCounts": dict(sorted(counts.items())),
        "incompleteCount": len(incomplete),
        "firstIncomplete": incomplete[:12],
    }
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, separators=(",", ":")))
    return 0 if not incomplete else 1


if __name__ == "__main__":
    sys.exit(main())
