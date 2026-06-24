#!/usr/bin/env python3
"""BlockPrint Link WebSocket v2 protocol smoke test.

Smoke-tests the three core operations against a running bridge:
  1. list       — enumerate schematics
  2. download   — fetch a file and verify SHA
  3. upload     — push a file and verify server-computed SHA

Usage:
  python ws_smoke_test.py --token <TOKEN> --host 127.0.0.1 --port 18080 \\
      [--download <fileName>] [--upload <localPath>]

If --download / --upload are omitted, those steps are skipped.
The token can also be provided via the BLOCKPRINT_TOKEN env var so the
script doesn't leak it in shell history.
"""
import argparse
import asyncio
import hashlib
import json
import os
import sys

import websockets


async def recv_text(ws, expected_type=None, expected_request_id=None, timeout=10):
    """Read text frames until one matches the filters (or any if None)."""
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
        if isinstance(raw, bytes):
            raise RuntimeError(f"expected text, got binary ({len(raw)} bytes)")
        msg = json.loads(raw)
        if expected_request_id and msg.get("requestId") != expected_request_id:
            print(f"  [skip] {msg}")
            continue
        if expected_type and msg.get("type") != expected_type:
            print(f"  [skip] {msg}")
            continue
        return msg


async def recv_binary(ws, expected_size, chunk_log_every=1):
    """Read binary frames until accumulated bytes reach expected_size."""
    chunks = []
    while sum(len(c) for c in chunks) < expected_size:
        raw = await asyncio.wait_for(ws.recv(), timeout=30)
        if not isinstance(raw, bytes):
            raise RuntimeError(f"expected binary, got text: {raw!r}")
        chunks.append(raw)
        total = sum(len(c) for c in chunks)
        if total % (chunk_log_every * 65536) < 65536 or total == expected_size:
            print(f"  [binary] {len(raw):>6} bytes  total {total:>7}/{expected_size}")
    return b"".join(chunks)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


async def run_list(ws):
    print("\n=== list")
    await ws.send(json.dumps({"type": "list", "requestId": "r1"}))
    msg = await recv_text(ws, expected_type="list/response", expected_request_id="r1")
    entries = msg.get("entries", [])
    meta = {k: msg[k] for k in ("mcVersion", "loader", "loaderVersion", "folderName") if k in msg}
    print(f"  bridge: {meta}")
    print(f"  found {len(entries)} files:")
    for e in entries[:5]:
        print(f"    {e['fileName']:<40} {e['format']:<10} "
              f"{e['width']}x{e['height']}x{e['depth']:<5} source={e['source']}")
    if len(entries) > 5:
        print(f"    ... and {len(entries) - 5} more")
    return entries


async def run_download(ws, file_name):
    print(f"\n=== download {file_name}")
    await ws.send(json.dumps({"type": "download", "requestId": "r2", "fileName": file_name}))
    ready = await recv_text(ws, expected_type="download/ready", expected_request_id="r2")
    size = ready["size"]
    server_sha = ready["sha256"]
    print(f"  ready: {size} bytes, sha256={server_sha[:16]}...")
    data = await recv_binary(ws, size)
    done = await recv_text(ws, expected_type="download/done", expected_request_id="r2")
    if not done.get("ok"):
        print(f"  done FAILED: {done}")
        return
    actual = sha256_hex(data)
    match = "OK" if actual == done["sha256"] else "MISMATCH"
    print(f"  done: bytes={done['bytes']}, sha256 verify {match}")
    print(f"    client={actual[:16]}...  server={done['sha256'][:16]}...")


async def run_upload(ws, local_path):
    print(f"\n=== upload {local_path}")
    with open(local_path, "rb") as f:
        data = f.read()
    size = len(data)
    sha = sha256_hex(data)
    name = os.path.basename(local_path)
    print(f"  local: {size} bytes, sha256={sha[:16]}...")

    await ws.send(json.dumps({
        "type": "upload/init", "requestId": "t1",
        "fileName": name, "size": size,
        "sha256": sha, "overwrite": "true"
    }))
    ready = await recv_text(ws, expected_type="upload/ready", expected_request_id="t1")
    if ready.get("fileName") != name:
        print(f"  ready fileName mismatch: {ready.get('fileName')}")
    print(f"  ready: {ready.get('fileName')}")

    # Stream in 64 KiB chunks.
    offset = 0
    while offset < size:
        await ws.send(data[offset:offset + 65536])
        offset = min(offset + 65536, size)
        if offset == size or offset % (1024 * 1024) < 65536:
            print(f"  [binary] sent {offset}/{size}")

    await ws.send(json.dumps({
        "type": "upload/commit", "requestId": "t1", "fileName": name
    }))
    result = await recv_text(ws, expected_type="upload/result", expected_request_id="t1")
    if not result.get("ok"):
        print(f"  result FAILED: {result.get('error')}")
        return
    done = await recv_text(ws, expected_type="upload/done", expected_request_id="t1")
    match = "OK" if done["sha256"] == sha else "MISMATCH"
    print(f"  done: sha256={done['sha256'][:16]}... verify {match}")
    print(f"    client={sha[:16]}...  server={done['sha256'][:16]}...")


async def main(args):
    token = args.token or os.environ.get("BLOCKPRINT_TOKEN")
    if not token:
        print("ERROR: --token or BLOCKPRINT_TOKEN env var required", file=sys.stderr)
        return 2
    url = f"ws://{args.host}:{args.port}/ws?token={token}"
    print(f"=== connecting to {args.host}:{args.port} (max_size={args.max_size_mb} MiB)")

    # max_size controls the *single-frame* size limit. Upload/download bodies
    # can be much larger; they arrive as multiple binary frames which we
    # reassemble manually via recv_binary. Setting max_size high avoids
    # websocket-server-side frame size rejection.
    async with websockets.connect(url, max_size=args.max_size_mb * 1024 * 1024) as ws:
        await run_list(ws)
        if args.download:
            await run_download(ws, args.download)
        if args.upload:
            await run_upload(ws, args.upload)

    print("\n=== done")
    return 0


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    p.add_argument("--token", help="bridge token (or set BLOCKPRINT_TOKEN env)")
    p.add_argument("--host", default="127.0.0.1", help="bridge host (default 127.0.0.1)")
    p.add_argument("--port", type=int, default=18080, help="bridge WS port (default 18080)")
    p.add_argument("--download", metavar="FILENAME", help="download this file from bridge")
    p.add_argument("--upload", metavar="LOCALPATH", help="upload this local file to bridge")
    p.add_argument("--max-size-mb", type=int, default=200,
                   help="WS frame size limit in MiB (default 200)")
    return p.parse_args()


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main(parse_args())))
    except KeyboardInterrupt:
        print("\ninterrupted")
        sys.exit(130)
    except Exception as e:
        print(f"!!! failed: {e}")
        sys.exit(1)
