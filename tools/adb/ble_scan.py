import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / 'python-deps'))
from bleak import BleakScanner


async def main():
    devices = await BleakScanner.discover(timeout=15, return_adv=True)
    for device, adv in devices.values():
        print(json.dumps({
            'address': device.address,
            'name': adv.local_name or device.name,
            'rssi': adv.rssi,
            'services': adv.service_uuids,
        }, ensure_ascii=False))


asyncio.run(main())
