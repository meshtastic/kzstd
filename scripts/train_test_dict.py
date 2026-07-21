#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate a small, TAK-free TRAINED zstd dictionary for kzstd's test suite.

Deterministic: a fixed RNG seed produces the same synthetic corpus, so the
committed dictionary is reproducible and auditable (no opaque blob). The corpus
is generic structured key/value records — no CoT/TAK content.

Usage:
    python3 scripts/train_test_dict.py            # prints magic + base64 dict
    python3 scripts/train_test_dict.py out.zstd   # also writes the dict file

Requires the `zstd` CLI (>= 1.4) on PATH. The resulting dictionary begins with
the trained-dict magic 0xEC30A437 (bytes 37 A4 30 EC) and carries real Huffman +
FSE entropy tables — which is what exercises kzstd's dictionary-entropy decode
path (treeless literals, FSE-repeat mode, repeat-offset seeding) in the libzstd
interop oracle.
"""
import base64
import os
import random
import subprocess
import sys
import tempfile

SEED = 0x6B7A7374  # "kzst"
N_SAMPLES = 1200
MAXDICT = 8192

TYPES = ["event", "telemetry", "status", "report", "heartbeat", "alert"]
STATES = ["ok", "warn", "degraded", "offline", "recovering", "unknown"]
WORDS = (
    "the quick brown fox jumps over a lazy dog while the system reports nominal "
    "throughput and stable latency across every monitored link in the region"
).split()


def sample(rng: random.Random) -> bytes:
    seq = rng.randint(0, 1_000_000)
    node = rng.randint(1, 64)
    t = rng.choice(TYPES)
    st = rng.choice(STATES)
    lat = rng.uniform(-90, 90)
    lon = rng.uniform(-180, 180)
    nwords = rng.randint(3, 12)
    msg = " ".join(rng.choice(WORDS) for _ in range(nwords))
    rec = (
        '{"type":"%s","seq":%d,"node":"node-%02d","state":"%s",'
        '"lat":%.5f,"lon":%.5f,"msg":"%s"}'
    ) % (t, seq, node, st, lat, lon, msg)
    return rec.encode("utf-8")


def main() -> int:
    rng = random.Random(SEED)
    with tempfile.TemporaryDirectory() as d:
        for i in range(N_SAMPLES):
            with open(os.path.join(d, "s%04d.json" % i), "wb") as f:
                f.write(sample(rng))
        out = os.path.join(d, "dict.zstd")
        files = [os.path.join(d, n) for n in sorted(os.listdir(d)) if n.endswith(".json")]
        subprocess.run(
            ["zstd", "--train", *files, "-o", out, "--maxdict=%d" % MAXDICT, "-f"],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        data = open(out, "rb").read()

    magic = data[:4]
    sys.stderr.write("dict bytes=%d magic=%s\n" % (len(data), magic.hex()))
    if magic != bytes([0x37, 0xA4, 0x30, 0xEC]):
        sys.stderr.write("ERROR: not a trained dict (bad magic)\n")
        return 1

    if len(sys.argv) > 1:
        open(sys.argv[1], "wb").write(data)
    print(base64.b64encode(data).decode("ascii"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
