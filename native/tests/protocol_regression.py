"""Reject malformed wire messages. Uses numeric.cvm produced by NativeBoundaryTest.

Run with --vm pointing to either the normal executable or an ASan/UBSan build.
"""
import argparse
import os
from pathlib import Path
import struct
import subprocess


def varint(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def text(value):
    data = value.encode()
    return varint(len(data)) + data


def message(kind, body=b""):
    return struct.pack("<I", len(body) + 1) + bytes([kind]) + body


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vm", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, default=root / "colony-dsl-parser/build/sessions/numeric.cvm")
    args = parser.parse_args()
    init = message(1, struct.pack("<H", 1) + text("malformed") + text("numeric") + text("Numeric")
                   + struct.pack("<q", 426) + args.artifact.read_bytes()[-32:])
    cases = {
        "overflowing sender length": message(3, b"\x00\x01\x00" + varint(2**64 - 1)),
        "overflowing varint": message(3, b"\xff" * 9 + b"\x02"),
        "unterminated varint": message(3, b"\x80" * 10),
        "truncated varint": message(3, b"\x80"),
        "truncated event": message(3, b"\x00\x01"),
        "too many events": message(3, b"\x00" + varint(65537)),
        "unknown event": message(3, b"\x00\x01" + varint(2**32 - 1) + text("test") + b"\x00"),
        "trailing frame bytes": message(3, b"\x00\x00\x00"),
        "trailing stop bytes": message(5, b"\x00"),
        "duplicate init": init,
        "unknown message": message(255),
        "empty message": struct.pack("<I", 0),
        "oversized message": struct.pack("<I", 64 * 1024 * 1024 + 1),
        "truncated header": b"\x01\x00",
        "truncated body": struct.pack("<I", 20) + b"\x03",
    }
    env = dict(os.environ, ASAN_OPTIONS="detect_leaks=1:halt_on_error=1",
               UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
    for name, payload in cases.items():
        result = subprocess.run([str(args.vm.resolve()), "--artifact", str(args.artifact.resolve())],
                                input=init + payload, capture_output=True, timeout=15, env=env)
        assert result.returncode == 4, (name, result.returncode, result.stderr.decode(errors="replace"))
        assert result.stderr.startswith(b"fault: "), (name, result.stderr)
        assert b"Sanitizer" not in result.stderr and b"runtime error:" not in result.stderr, (name, result.stderr)
        output, kinds = result.stdout, []
        while output:
            assert len(output) >= 5, (name, output)
            length = struct.unpack_from("<I", output)[0]
            assert 1 <= length <= len(output) - 4, (name, length)
            kinds.append(output[4])
            output = output[4 + length:]
        assert kinds == [2, 6], (name, kinds)  # READY, then FAULT; never a partial RESULT.
    print(f"Protocol regression: {len(cases)} malformed messages rejected; no sanitizer diagnostics.")


if __name__ == "__main__":
    main()
