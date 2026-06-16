#!/usr/bin/env python3
"""Listen on UDP :18081 and dump every blockprintlink/discovery packet.

Usage: python listen_udp_broadcast.py [--seconds N]
Default runs for 30 seconds, prints each packet as it arrives, then exits.
"""
import argparse
import json
import socket
import sys
import time


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=int, default=30,
                        help="how long to listen (default 30s)")
    parser.add_argument("--port", type=int, default=18081,
                        help="UDP port to bind (default 18081)")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    # SO_BROADCAST isn't required for receiving, but harmless; keep it off.
    sock.bind(("0.0.0.0", args.port))
    sock.settimeout(1.0)

    print(f"[listen] bound 0.0.0.0:{args.port}, capturing for {args.seconds}s...")
    sys.stdout.flush()

    deadline = time.monotonic() + args.seconds
    count = 0
    try:
        while time.monotonic() < deadline:
            try:
                data, addr = sock.recvfrom(2048)
            except socket.timeout:
                continue
            count += 1
            try:
                text = data.decode("utf-8", errors="replace")
                pretty = json.dumps(json.loads(text), indent=2, ensure_ascii=False)
                print(f"[{count:03d}] from {addr[0]}:{addr[1]} ({len(data)} bytes)")
                print(pretty)
            except json.JSONDecodeError:
                print(f"[{count:03d}] from {addr[0]}:{addr[1]} ({len(data)} bytes, non-JSON)")
                print(repr(data))
            sys.stdout.flush()
    except KeyboardInterrupt:
        print("\n[listen] interrupted")
    finally:
        sock.close()
        print(f"[listen] done. total packets: {count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())