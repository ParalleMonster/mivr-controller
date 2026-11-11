import { readFileSync } from "node:fs";

const [, , path, clockOffset] = process.argv;
const offsetMatch = /^([+-])([01]\d|2[0-3]):([0-5]\d)$/.exec(clockOffset ?? "+00:00");
if (!path || !offsetMatch || process.argv.length > 4) {
  console.error("Usage: node tools/read-smp.mjs <btsnoop_hci.log | -> [clock offset, e.g. +08:00]");
  process.exit(2);
}
const [, sign, hours, minutes] = offsetMatch;
const clockOffsetMs = (sign === "-" ? -1 : 1) * (Number(hours) * 60 + Number(minutes)) * 60_000;

const capture = readFileSync(path === "-" ? 0 : path);
const magic = Buffer.from("btsnoop\0");
if (capture.length < 16 || !capture.subarray(0, 8).equals(magic) ||
    capture.readUInt32BE(8) !== 1 || capture.readUInt32BE(12) !== 1002) {
  throw new Error("Expected a version 1 HCI H4 btsnoop file (link type 1002)");
}

const epoch = 0x00dcddb30f2f8000n;
const peers = new Map();
const capabilities = ["DisplayOnly", "DisplayYesNo", "KeyboardOnly", "NoInputNoOutput", "KeyboardDisplay"];
const hex = (value, width = 2) => `0x${value.toString(16).padStart(width, "0")}`;
let smpCount = 0;

for (let offset = 16; offset + 24 <= capture.length;) {
  const length = capture.readUInt32BE(offset + 4);
  const flags = capture.readUInt32BE(offset + 8);
  const micros = capture.readBigUInt64BE(offset + 16) - epoch;
  const timestamp = new Date(Number(micros / 1000n) - clockOffsetMs).toISOString();
  const start = offset + 24;
  offset = start + length;
  if (offset > capture.length) throw new Error("Truncated btsnoop record");
  const packet = capture.subarray(start, offset);

  if (packet[0] === 4) {
    const event = packet[1];
    if (event === 0x3e && packet.length >= 15 && [1, 10, 41].includes(packet[3]) && packet[4] === 0) {
      const handle = packet.readUInt16LE(5) & 0xfff;
      const suffix = `${hex(packet[10]).slice(2)}:${hex(packet[9]).slice(2)}`.toUpperCase();
      peers.set(handle, suffix);
    } else if (event === 5 && packet.length >= 7) {
      peers.delete(packet.readUInt16LE(4) & 0xfff);
    }
    continue;
  }

  if (packet[0] !== 2 || packet.length < 10) continue;
  const handleAndFlags = packet.readUInt16LE(1);
  const boundary = (handleAndFlags >> 12) & 3;
  if (boundary === 1 || packet.readUInt16LE(7) !== 6) continue;
  const payloadLength = packet.readUInt16LE(5);
  const smp = packet.subarray(9, 9 + payloadLength);
  const opcode = smp[0];
  const handle = handleAndFlags & 0xfff;
  const peer = peers.get(handle) ?? "unknown";
  const direction = flags & 1 ? "RX" : "TX";
  const prefix = `${timestamp} ${direction} handle=${hex(handle, 3)} peer-suffix=${peer}`;

  if ((opcode === 1 || opcode === 2) && smp.length === 7) {
    const [, io, oob, auth, size, initiatorKeys, responderKeys] = smp;
    const message = opcode === 1 ? "Pairing Request" : "Pairing Response";
    const security = `bond=${auth & 3} MITM=${(auth >> 2) & 1} SC=${(auth >> 3) & 1}`;
    console.log(`${prefix} ${message} IO=${capabilities[io] ?? hex(io)} OOB=${oob} AuthReq=${hex(auth)} ${security} max-key-size=${size} initiator-keys=${hex(initiatorKeys)} responder-keys=${hex(responderKeys)}`);
    smpCount++;
  } else if (opcode === 5 && smp.length === 2) {
    console.log(`${prefix} Pairing Failed reason=${hex(smp[1])}`);
    smpCount++;
  } else if (opcode === 13 && payloadLength === 17) {
    console.log(`${prefix} DHKey Check`);
    smpCount++;
  }
}

if (smpCount === 0) console.log("No matching LE SMP records.");
