param(
    [string]$Serial,
    [string]$Adb = "$PSScriptRoot/../.toolchain/official-adb/platform-tools/adb.exe",
    [string]$Injector = "$PSScriptRoot/../.toolchain/frida-inject-17.26.0-android-arm64",
    [ValidateRange(30, 600)]
    [int]$Seconds = 240
)

$ErrorActionPreference = 'Stop'
$Adb = (Resolve-Path -LiteralPath $Adb).Path
$Injector = (Resolve-Path -LiteralPath $Injector).Path
$target = if ($Serial) { @('-s', $Serial) } else { @() }
$agent = Join-Path $PSScriptRoot 'redmi-static-random.js'
$buildHash = '96a3560efe338bcb89b312c094b5c5fd9dfb08827b3a889a810b6e4ad3ce525d'
$injectorHash = 'eba055d8ec1e9162ffe4c79b2950229fbc9f4a31bad46f3fcd5d739d925a4270'
$token = [Guid]::NewGuid().ToString('N')
$remoteInjector = "/data/local/tmp/mivr-inject-$token"
$remoteAgent = "/data/local/tmp/mivr-address-$token.js"

if ((Get-FileHash -LiteralPath $Injector).Hash -ne $injectorHash) {
    throw 'Expected the verified Frida 17.26.0 Android ARM64 injector.'
}

$device = & $Adb @target shell getprop ro.product.device
if ($LASTEXITCODE -ne 0 -or $device -ne 'corot') {
    throw 'The selected ADB device must be a Redmi K60 Ultra (corot).'
}
$hash = ((& $Adb @target shell sha256sum /apex/com.android.bt/lib64/libbluetooth_jni.so) -split ' ')[0]
if ($LASTEXITCODE -ne 0 -or $hash -ne $buildHash) {
    throw 'The Bluetooth library differs from the verified build.'
}

try {
    & $Adb @target push $Injector $remoteInjector
    if ($LASTEXITCODE -ne 0) { throw 'Failed to transfer the injector.' }
    & $Adb @target push $agent $remoteAgent
    if ($LASTEXITCODE -ne 0) { throw 'Failed to transfer the probe.' }
    & $Adb @target shell chmod 755 $remoteInjector
    if ($LASTEXITCODE -ne 0) { throw 'Failed to make the injector executable.' }

    $command = @'
set -e
p=$(pidof -s com.android.bluetooth)
test -n "$p"
test "$(sha256sum __INJECTOR__ | cut -d ' ' -f 1)" = '__INJECTOR_HASH__'
nsenter -t "$p" -m -- grep -Fxq 'PTS_SmpOptions=0x09,0x03,0x0f,0x0f,0x10' /apex/com.android.bt/etc/bluetooth/bt_stack.conf
bb=/data/adb/ap/bin/busybox
exec "$bb" unshare -m "$bb" sh -c "set -e; $bb mount --make-rprivate /; $bb mount -t tmpfs -o ro tmpfs /sys/fs/selinux; test ! -e /sys/fs/selinux/load; exec timeout -s INT __SECONDS__ __INJECTOR__ -p $p -s __AGENT__"
'@
    $command = $command.Replace('__SECONDS__', "$Seconds").Replace('__INJECTOR__', $remoteInjector)
    $command = $command.Replace('__INJECTOR_HASH__', $injectorHash).Replace('__AGENT__', $remoteAgent)
    $quoted = "'" + $command.Replace("'", "'\''") + "'"
    & $Adb @target shell -T "su -c $quoted"
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 124) {
        throw "The probe exited with code $LASTEXITCODE. Start V0.11 in its default no-code mode before attaching."
    }
} finally {
    & $Adb @target shell rm -f $remoteAgent $remoteInjector
}
