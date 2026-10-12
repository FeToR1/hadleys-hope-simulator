#!/usr/bin/env python3
"""Replay frozen CVM2 vectors via the installed CLI; verify the PRNG independently.

There is one executor, hh-vm. This script is a regression runner, not another VM.
Build native/ and run Gradle installDist first. Usage: python run_vectors.py [VECTOR_DIR]
"""
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys


def verify_prng(path):
    vectors = json.loads(path.read_text(encoding="utf-8"))["vectors"]
    for v in vectors:
        data = struct.pack(">q", v["seed"])
        for key in ("entityId", "behavior", "rule", "site"):
            raw = v[key].encode("utf-8")
            data += struct.pack(">I", len(raw)) + raw
        data += struct.pack(">q", v["counter"])
        digest = hashlib.sha256(data).digest()[:8]
        bits = int.from_bytes(digest, "big") >> 11
        unit = bits / 2**53
        assert data.hex() == v["input"], v
        assert digest.hex() == v["digestHead"], v
        assert str(bits) == v["bits53"], v
        assert repr(unit) == v["unit"], v
        assert struct.pack(">d", unit).hex() == v["unitBits"], v
    print(f"Independent PRNG: {len(vectors)} checks OK")


def main():
    repository = Path(__file__).resolve().parents[1]
    vectors = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else repository / "conformance"
    verify_prng(vectors / "prng.json")
    library = repository / "colony-dsl-parser/build/install/colony-dsl-parser/lib"
    jars = sorted(library.glob("*.jar"))
    if not jars:
        raise SystemExit("Build CLI first: colony-dsl-parser/gradlew.bat -p colony-dsl-parser installDist")
    result = subprocess.run(["java", "-cp", os.pathsep.join(str(p) for p in jars),
                             "colony.cli.MainKt", "verify-conformance", str(vectors)],
                            cwd=repository, timeout=180)
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
