#!/usr/bin/env python3
"""
xpida_client.py - Remote TCP client for XpidaService

Protocol:
  Request:  [MAGIC 4B] [CMD 1B] [SEQ 2B] [PAYLOAD_LEN 4B] [PAYLOAD ...]
  Response: [MAGIC 4B] [STATUS 1B] [SEQ 2B] [PAYLOAD_LEN 4B] [PAYLOAD ...]

  MAGIC = 0x58504441 ("XPDA"), big-endian
"""

import socket
import struct
import sys
import os

MAGIC = 0x58504441
HEADER_FMT = ">IBhI"  # big-endian: uint32 magic, uint8 cmd/status, int16 seq, uint32 payload_len
HEADER_SIZE = struct.calcsize(HEADER_FMT)

CMD_PING = 0x01
CMD_PS   = 0x02
CMD_FIND = 0x03
CMD_MAPS = 0x04
CMD_READ = 0x05
CMD_DUMP = 0x06

STATUS_OK   = 0x00
STATUS_FAIL = 0x01
STATUS_MORE = 0x02  # chunked transfer: more chunks follow

CMD_MAP = {
    "ping": CMD_PING,
    "ps":   CMD_PS,
    "find": CMD_FIND,
    "maps": CMD_MAPS,
    "read": CMD_READ,
    "dump": CMD_DUMP,
}

BINARY_CMDS = {"read", "dump"}


class XpidaClient:
    def __init__(self, host: str, port: int = 9527, timeout: float = 30.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self._sock = None
        self._seq = 0

    def connect(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.settimeout(self.timeout)
        self._sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._sock.connect((self.host, self.port))

    def close(self):
        if self._sock:
            self._sock.close()
            self._sock = None

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *args):
        self.close()

    def _next_seq(self) -> int:
        self._seq = (self._seq + 1) & 0x7FFF
        return self._seq

    def _send_request(self, cmd: int, payload: bytes = b""):
        seq = self._next_seq()
        header = struct.pack(HEADER_FMT, MAGIC, cmd, seq, len(payload))
        self._sock.sendall(header + payload)
        return seq

    def _recv_exactly(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("Connection closed by remote")
            buf.extend(chunk)
        return bytes(buf)

    def _recv_response(self) -> tuple:
        """Returns (status, seq, payload_bytes)"""
        header = self._recv_exactly(HEADER_SIZE)
        magic, status, seq, payload_len = struct.unpack(HEADER_FMT, header)
        if magic != MAGIC:
            raise ValueError(f"Bad magic: 0x{magic:08x}")
        if payload_len > 128 * 1024 * 1024:
            raise ValueError(f"Payload too large: {payload_len}")
        payload = self._recv_exactly(payload_len) if payload_len > 0 else b""
        return status, seq, payload

    def execute(self, cmd: int, payload: bytes = b"") -> tuple:
        """Send command, return (status, data_bytes)"""
        self._send_request(cmd, payload)
        status, seq, data = self._recv_response()
        return status, data

    # --- Convenience methods ---

    def ping(self) -> str:
        status, data = self.execute(CMD_PING)
        return data.decode("utf-8", errors="replace")

    def ps(self) -> str:
        status, data = self.execute(CMD_PS)
        return data.decode("utf-8", errors="replace")

    def find(self, name: str) -> str:
        status, data = self.execute(CMD_FIND, name.encode())
        return data.decode("utf-8", errors="replace")

    def maps(self, pid: int) -> str:
        status, data = self.execute(CMD_MAPS, str(pid).encode())
        return data.decode("utf-8", errors="replace")

    def read_memory(self, pid: int, addr: int, size: int) -> bytes:
        payload = f"{pid} {addr:x} {size}".encode()
        status, data = self.execute(CMD_READ, payload)
        if status != STATUS_OK:
            raise RuntimeError(f"read failed: {data.decode(errors='replace')}")
        return data

    def dump_memory(self, pid: int, start: int, end: int, output_file: str = None) -> bytes:
        """
        Streamed dump: server sends multiple STATUS_MORE chunks, final chunk is STATUS_OK.
        If output_file is given, writes chunks to file progressively (constant memory).
        Otherwise accumulates and returns all bytes.
        """
        payload = f"{pid} {start:x} {end:x}".encode()
        self._send_request(CMD_DUMP, payload)

        f = open(output_file, "wb") if output_file else None
        chunks = [] if not output_file else None
        total = 0

        try:
            while True:
                status, seq, data = self._recv_response()

                if status == STATUS_FAIL:
                    raise RuntimeError(f"dump failed: {data.decode(errors='replace')}")

                if f:
                    f.write(data)
                else:
                    chunks.append(data)
                total += len(data)

                print(f"\r  [{total:,} bytes received]", end="", file=sys.stderr)

                if status == STATUS_OK:
                    break
                # STATUS_MORE -> keep reading
        finally:
            if f:
                f.close()

        print(file=sys.stderr)  # newline after progress

        if output_file:
            return total.to_bytes(8, "big")  # return byte count as marker
        return b"".join(chunks)


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <host> <command> [args...]")
        print()
        print("Commands:")
        print("  ping")
        print("  ps")
        print("  find <process_name>")
        print("  maps <pid>")
        print("  read <pid> <hex_addr> <size>")
        print("  dump <pid> <hex_start> <hex_end> [output_file]")
        sys.exit(1)

    host = sys.argv[1]
    cmd_name = sys.argv[2].lower()

    if cmd_name not in CMD_MAP:
        print(f"Unknown command: {cmd_name}")
        sys.exit(1)

    with XpidaClient(host) as client:
        if cmd_name == "ping":
            print(client.ping())

        elif cmd_name == "ps":
            print(client.ps())

        elif cmd_name == "find":
            if len(sys.argv) < 4:
                print("Usage: find <process_name>")
                sys.exit(1)
            print(client.find(sys.argv[3]))

        elif cmd_name == "maps":
            if len(sys.argv) < 4:
                print("Usage: maps <pid>")
                sys.exit(1)
            print(client.maps(int(sys.argv[3])))

        elif cmd_name == "read":
            if len(sys.argv) < 6:
                print("Usage: read <pid> <hex_addr> <size>")
                sys.exit(1)
            pid = int(sys.argv[3])
            addr = int(sys.argv[4], 16)
            size = int(sys.argv[5])
            data = client.read_memory(pid, addr, size)
            def hexdump(data, base_addr):
                        for i in range(0, len(data), 16):
                            hex_part = ' '.join(f'{b:02x}' for b in data[i:i+16])
                            ascii_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[i:i+16])
                            print(f'{base_addr+i:08x}  {hex_part:<48}  |{ascii_part}|')

            hexdump(data, addr)
            print(f"\n[{len(data)} bytes read]", file=sys.stderr)

        elif cmd_name == "dump":
            if len(sys.argv) < 6:
                print("Usage: dump <pid> <hex_start> <hex_end> [output_file]")
                sys.exit(1)
            pid = int(sys.argv[3])
            start = int(sys.argv[4], 16)
            end = int(sys.argv[5], 16)
            output_file = sys.argv[6] if len(sys.argv) > 6 else None

            if output_file:
                client.dump_memory(pid, start, end, output_file=output_file)
                print(f"[written to {output_file}]", file=sys.stderr)
            else:
                data = client.dump_memory(pid, start, end)
                sys.stdout.buffer.write(data)
                print(f"\n[{len(data)} bytes dumped]", file=sys.stderr)


if __name__ == "__main__":
    main()
