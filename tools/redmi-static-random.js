// Frida probe for the verified Bluetooth builds. The standalone Redmi tool uses corot.
const bluetooth = Process.getModuleByName("libbluetooth_jni.so");
const builds = {
    corot: {
        create: 0x447f10, address: 0x4422c0, remove: 0x43ae20,
        prologue: 0xf800865e, packedReturn: false, text: "F2:6A:A7:11:53:CB",
    },
    pandora: {
        create: 0xcd9ad8, address: 0xcd8630, remove: 0xcd47e0,
        prologue: 0xd503233f, packedReturn: true, text: "EA:27:54:8C:39:B1",
    },
};
const build = builds[typeof mivrProfile === "undefined" ? "corot" : mivrProfile];
if (!build) throw new Error("Unsupported Bluetooth profile");
const createAdvertiser = bluetooth.base.add(build.create);
const newAdvertiserAddress = bluetooth.base.add(build.address);
const removeAdvertiser = bluetooth.base.add(build.remove);
if ([createAdvertiser, newAdvertiserAddress, removeAdvertiser]
    .some((entry) => entry.readU32() !== build.prologue)) {
    throw new Error("Unsupported Bluetooth build");
}

const mivrAdvertisers = new Set();
const manufacturer = [0x8e, 0x05, 0x00];
const controllerUuid = [0xb3, 0x83, 0x3c, 0x22, 0x0f, 0x27, 0x11, 0xe7, 0x93, 0xae, 0x92, 0x36, 0x52, 0x56, 0x26, 0x81];
const staticAddress = build.text.split(":").reverse().map((byte) => parseInt(byte, 16));

function advertiserKey(manager, id) {
    return manager.toString() + ":" + (id.toInt32() & 0xff);
}

function matchesGapData(entry, type, bytes) {
    if (entry.add(16).readU8() !== type) return false;
    const begin = entry.add(24).readPointer();
    const end = entry.add(32).readPointer();
    return end.equals(begin.add(bytes.length)) && bytes.every((byte, index) => begin.add(index).readU8() === byte);
}

Interceptor.attach(createAdvertiser, {
    onEnter(args) {
        const key = advertiserKey(args[0], args[3]);
        mivrAdvertisers.delete(key);
        const config = args[4];
        const begin = config.readPointer();
        const end = config.add(8).readPointer();
        const size = end.sub(begin).toInt32();
        const gapDataSize = 56;
        if (size < gapDataSize || size > gapDataSize * 16 || size % gapDataSize !== 0) return;

        let hasManufacturer = false;
        let hasControllerUuid = false;
        for (let offset = 0; offset < size; offset += gapDataSize) {
            const entry = begin.add(offset);
            hasManufacturer ||= matchesGapData(entry, 0xff, manufacturer);
            hasControllerUuid ||= matchesGapData(entry, 0x06, controllerUuid) ||
                matchesGapData(entry, 0x07, controllerUuid);
        }
        if (hasManufacturer && hasControllerUuid) {
            mivrAdvertisers.add(key);
            console.log("Matched MiVR advertiser");
        }
    },
});

Interceptor.attach(newAdvertiserAddress, {
    onEnter(args) {
        const key = advertiserKey(args[0], args[1]);
        this.mivr = mivrAdvertisers.has(key);
        // On corot the C++ return object's storage is passed in x8.
        if (this.mivr && !build.packedReturn) this.addressResult = this.context.x8;
    },
    onLeave(returnValue) {
        if (!this.mivr) return;
        const type = build.packedReturn
            ? returnValue.shr(48).and(ptr(0xff)).toInt32()
            : this.addressResult.add(0x28).readU8();
        if (type !== 1) {
            console.log("MiVR address type is not random");
            return;
        }
        if (build.packedReturn) {
            returnValue.replace(ptr("0x01" + build.text.replace(/:/g, "")));
        } else {
            this.addressResult.add(0x20).writeByteArray(staticAddress);
        }
        console.log("MiVR static address " + build.text);
    },
});

Interceptor.attach(removeAdvertiser, {
    onEnter(args) {
        mivrAdvertisers.delete(advertiserKey(args[0], args[1]));
    },
});

console.log("MiVR static random ready");
