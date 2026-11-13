"""Authenticate to the Mi VR companion service and query/set developer mode.

Uses the Xiaomi token path implemented by the headset's MiAuthServer:
  method 3 (XIAOMI_AUTHENTICATE), body field 1 = secret (bound device secret)
  response body field 1 = session token
  method 6002 (DEV_MODE_STATUS), request field 5 = token
  method 6001 (DEV_MODE_SET), body field 1 = mode (1 enable, 0 disable)

This is a local BLE test tool for the owner's headset. It does not flash,
erase, or change the bootloader.
"""
import argparse
import asyncio
import json
import struct
import sys
import time

from pathlib import Path
DEPS = str(Path(__file__).resolve().parent / 'python-deps')
sys.path.insert(0, DEPS)
from bleak import BleakClient, BleakScanner

CCS = '7a442881-509c-47fa-ac02-b06a37d9eb76'
METHOD_AUTH = 3
METHOD_DEV_MODE_SET = 6001
METHOD_DEV_MODE_STATUS = 6002


def varint(value):
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def integer(number, value):
    return varint(number << 3) + varint(value)


def blob(number, value):
    return varint(number << 3 | 2) + varint(len(value)) + value


def fields(data):
    pos = 0
    result = {}
    def readint():
        nonlocal pos
        value = shift = 0
        while pos < len(data) and shift < 70:
            part = data[pos]
            pos += 1
            value |= (part & 127) << shift
            if part < 128:
                return value
            shift += 7
        raise ValueError('Invalid varint')
    while pos < len(data):
        tag = readint()
        wire = tag & 7
        if wire == 0:
            value = readint()
        elif wire == 2:
            size = readint()
            if pos + size > len(data):
                raise ValueError('Truncated protobuf')
            value = data[pos:pos + size]
            pos += size
        else:
            raise ValueError(f'Unsupported protobuf wire type {wire}')
        result[tag >> 3] = value
    return result


def request(method, body, seq, token=None):
    data = integer(1, 0) + integer(2, method) + integer(3, seq) + blob(4, body)
    if token:
        data += blob(5, token.encode())
    return data


async def exchange(client, data):
    chunks = [data[n:n + 18] for n in range(0, len(data), 18)]
    for seq, chunk in enumerate(chunks):
        header = seq | (0x8000 if seq == len(chunks) - 1 else 0)
        await client.write_gatt_char(CCS, struct.pack('>H', header) + chunk, response=True)
    result = bytearray()
    expected = 0
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        part = bytes(await client.read_gatt_char(CCS))
        if part in (b'', b'\xff', b'----'):
            await asyncio.sleep(0.1)
            continue
        if len(part) < 2:
            raise ValueError('Short BLE frame')
        header = struct.unpack('>H', part[:2])[0]
        if header & 0x7fff != expected:
            raise ValueError('Out-of-order BLE frame')
        result.extend(part[2:])
        if len(result) > 65536:
            raise ValueError('Oversized response')
        if header & 0x8000:
            return bytes(result)
        expected += 1
    raise TimeoutError('Companion response timed out')


def decode_body(response):
    raw = response.get(3, b'')
    if not raw:
        return {}
    out = {}
    for key, value in fields(raw).items():
        if isinstance(value, bytes):
            out[key] = value.decode('utf-8', errors='replace')
        else:
            out[key] = value
    return out


def seq(response):
    return response.get(1, 0)


def code(response):
    return response.get(2, -1)


async def main(args):
    devices = await BleakScanner.discover(timeout=15, return_adv=True)
    match = None
    for device, adv in devices.values():
        if args.address and device.address.lower() == args.address.lower():
            match = device
            break
        if not args.address and (adv.local_name or device.name or '').lower() == 'v1o-5d4e':
            match = device
            break
    if not match:
        raise RuntimeError('Mi VR BLE device not found')

    print(f'Connected {match.address}', flush=True)
    async with BleakClient(match, timeout=25) as client:
        if client.services.get_characteristic(CCS) is None:
            raise RuntimeError('Companion characteristic is absent')

        auth = fields(await exchange(client, request(METHOD_AUTH, blob(1, args.secret.encode()), 1)))
        print('auth_code', code(auth), 'auth_seq', seq(auth), flush=True)
        if code(auth) != 0:
            print('auth_body', json.dumps(decode_body(auth), ensure_ascii=False), flush=True)
            raise RuntimeError('XIAOMI_AUTHENTICATE rejected')
        token = decode_body(auth).get(1, '')
        if not token:
            raise RuntimeError('empty session token')
        print('token_ok', len(token), flush=True)

        status = fields(await exchange(client, request(METHOD_DEV_MODE_STATUS, b'', 2, token)))
        print('status_code', code(status), 'status_seq', seq(status), flush=True)
        print('status_body', json.dumps(decode_body(status), ensure_ascii=False), flush=True)

        if args.enable or args.disable:
            mode = 1 if args.enable else 0
            set_resp = fields(await exchange(
                client, request(METHOD_DEV_MODE_SET, integer(1, mode), 3, token)
            ))
            print('set_code', code(set_resp), 'set_seq', seq(set_resp), flush=True)
            print('set_body', json.dumps(decode_body(set_resp), ensure_ascii=False), flush=True)

            status2 = fields(await exchange(
                client, request(METHOD_DEV_MODE_STATUS, b'', 4, token)
            ))
            print('status2_code', code(status2), 'status2_seq', seq(status2), flush=True)
            print('status2_body', json.dumps(decode_body(status2), ensure_ascii=False), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--address', required=True)
    parser.add_argument('--secret', required=True)
    parser.add_argument('--enable', action='store_true')
    parser.add_argument('--disable', action='store_true')
    asyncio.run(main(parser.parse_args()))
