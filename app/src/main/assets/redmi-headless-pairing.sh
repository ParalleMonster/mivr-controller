#!/system/bin/sh
# Run as root on a Bluetooth APEX with the stock SMP configuration template.
set -eu

target=/apex/com.android.bt/etc/bluetooth/bt_stack.conf
expected='PTS_SmpOptions=0x09,0x03,0x0f,0x0f,0x10'
zygote="$(pidof -s zygote64 || pidof -s zygote || :)"
state=/data/adb/mivr-headless-pairing
stage="$state/bt_stack.conf"
saved_hash="$state/original.sha256"
saved_privacy="$state/original.privacy"
privacy_prop=bluetooth.core.gap.le.privacy.enabled
resetprop=/data/adb/ap/bin/resetprop
mounted=0
restart=0
mode="${1:-}"
apk="${2:-}"
bb=/data/adb/ap/bin/busybox
advertiser="$state/advertiser"
injector="$advertiser/injector"
library=/apex/com.android.bt/lib64/libbluetooth_jni.so
profile="$(getprop ro.product.device)"
injector_hash=eba055d8ec1e9162ffe4c79b2950229fbc9f4a31bad46f3fcd5d739d925a4270
stock_smp='^[[:space:]]*#[[:space:]]*PTS_SmpOptions[[:space:]]*='

static_supported() {
    case "$profile" in
        corot) library_hash=96a3560efe338bcb89b312c094b5c5fd9dfb08827b3a889a810b6e4ad3ce525d ;;
        pandora) library_hash=7155b386a180f59bc77430e2f7aa405430d233a9a4487ac036dd0183da3fa1ad ;;
        *) return 1 ;;
    esac
    [ -x "$bb" ] && [ -f "$apk" ] &&
        [ "$(sha256sum "$library" | cut -d ' ' -f 1)" = "$library_hash" ]
}

stop_static_advertising() {
    [ -d "$advertiser" ] || return 0
    if [ -f "$advertiser/pid" ]; then
        helper="$(cat "$advertiser/pid")"
        case "$helper" in
            ''|*[!0-9]*) return 1 ;;
        esac
        if [ "$(readlink "/proc/$helper/exe" 2>/dev/null)" = "$injector" ]; then
            kill -INT "$helper" 2>/dev/null || :
            for attempt in 1 2 3 4 5 6 7 8 9 10; do
                [ "$(readlink "/proc/$helper/exe" 2>/dev/null)" = "$injector" ] || break
                sleep 0.1
            done
        fi
    fi
    # Bluetooth is restarted during restore, which also removes any installed hooks.
    rm -f "$injector" "$advertiser/probe.js" "$advertiser/log" "$advertiser/pid" &&
        rmdir "$advertiser"
}

start_static_advertising() {
    static_supported && mkdir "$advertiser" || return 1
    "$bb" unzip -p "$apk" assets/redmi-injector > "$injector" &&
        { printf 'const mivrProfile = "%s";\n' "$profile" &&
            "$bb" unzip -p "$apk" assets/redmi-static-random.js; } > "$advertiser/probe.js" &&
        [ "$(sha256sum "$injector" | cut -d ' ' -f 1)" = "$injector_hash" ] &&
        chmod 0700 "$injector" || return 1
    bt_pid="$(pidof -s com.android.bluetooth)" || return 1
    # Hide selinuxfs only inside the injector's mount namespace.
    "$bb" unshare -m "$bb" sh -c '
        set -e
        bb="$1"
        shift
        "$bb" mount --make-rprivate /
        "$bb" mount -t tmpfs -o ro tmpfs /sys/fs/selinux
        test ! -e /sys/fs/selinux/load
        exec "$@"
    ' mivr "$bb" "$injector" -p "$bt_pid" -s "$advertiser/probe.js" \
        </dev/null >"$advertiser/log" 2>&1 9>&- &
    helper=$!
    printf '%s\n' "$helper" > "$advertiser/pid" || return 1
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        kill -0 "$helper" 2>/dev/null || break
        grep -Fxq 'MiVR static random ready' "$advertiser/log" && return 0
        sleep 1
    done
    cat "$advertiser/log" >&2
    return 1
}

in_namespace() {
    nsenter -t "$zygote" -m -- "$@"
}

wait_for_bluetooth() {
    cmd bluetooth_manager "wait-for-state:$1"
}

has_config_mount() {
    in_namespace awk -v target="$target" '$5 == target { found=1 } END { exit !found }' /proc/self/mountinfo
}

privacy_is_set() {
    getprop | grep -Fq "[$privacy_prop]:"
}

restore_privacy() {
    [ -f "$saved_privacy" ] || return 0
    previous="$(cat "$saved_privacy")" || return 1
    case "$previous" in
        unset)
            if privacy_is_set; then "$resetprop" -d "$privacy_prop" || return 1; fi
            ! privacy_is_set
            ;;
        set:*)
            value="${previous#set:}"
            "$resetprop" -n "$privacy_prop" "$value" &&
                [ "$(getprop "$privacy_prop")" = "$value" ]
            ;;
        *) return 1 ;;
    esac
}

stack_has_override() {
    bt_pid="$(pidof -s com.android.bluetooth)" &&
        nsenter -t "$bt_pid" -m -- grep -Fxq "$expected" "$target"
}

wait_for_session() {
    if [ "$mode" = --app-static ] && ! start_static_advertising; then
        echo 'STATIC_ADDRESS_FAILED'
        return 1
    fi
    echo 'READY: connect the headset within 120 seconds.'
    if [ "$mode" = --app ] || [ "$mode" = --app-static ]; then
        if IFS= read -r -t 120 command && [ "$command" = KEEP ]; then
            echo 'CONNECTED'
            while IFS= read -r command; do :; done
        fi
    else
        sleep 120
    fi
}

restore_config() {
    restored=1
    stop_static_advertising || restored=0
    if [ "$restart" = 1 ]; then
        cmd bluetooth_manager disable || restored=0
        wait_for_bluetooth STATE_OFF || restored=0
    fi
    if [ "$mounted" = 1 ] && [ "$restored" = 1 ]; then
        in_namespace umount "$target" || restored=0
    fi
    restore_privacy || restored=0
    if [ "$restart" = 1 ]; then
        cmd bluetooth_manager enable || restored=0
        wait_for_bluetooth STATE_ON || restored=0
        if [ "$(in_namespace sha256sum "$target" | cut -d ' ' -f 1)" != "$original_hash" ]; then
            restored=0
        fi
    fi
    if [ "$restored" = 0 ]; then
        echo 'RESTORE_FAILED'
        return 1
    fi
    if [ "$restart" = 1 ]; then echo 'Original Bluetooth configuration restored.'; fi
    rm -f "$stage" "$saved_hash" "$saved_privacy" || return 1
    mounted=0
    restart=0
}

cleanup() {
    status=$?
    trap - EXIT HUP INT TERM
    set +e
    if [ "$mounted" = 1 ] && { [ "$mode" = --app ] || [ "$mode" = --app-static ]; }; then
        echo 'RESTORING'
        IFS= read -r -t 3 stopped || :
    fi
    restore_config || status=1
    exit "$status"
}

[ "$(id -u)" = 0 ] || { echo 'ROOT_REQUIRED'; exit 1; }
[ -n "$zygote" ] && in_namespace test -r "$target" ||
    { echo 'UNSUPPORTED_SMP'; exit 1; }
umask 077
mkdir -p "$state"
exec 9>"$state/guard"
flock -n 0 <&9 || { echo 'BUSY'; exit 1; }

if [ -f "$saved_hash" ]; then
    original_hash="$(cat "$saved_hash")"
    if has_config_mount; then
        [ -f "$stage" ] &&
            [ "$(stat -c '%d:%i' "$stage")" = "$(in_namespace stat -c '%d:%i' "$target")" ] &&
            in_namespace grep -Fxq "$expected" "$target" ||
            { echo 'An unrelated SMP override is already active.' >&2; exit 1; }
        mounted=1
    fi
    restart=1
    # A V0.8 fixed-address session must be restored before starting a pairing-only session.
    if { [ "$mode" = --app ] || [ "$mode" = --app-static ]; } &&
        [ "$mounted" = 1 ] && [ ! -f "$saved_privacy" ] && [ ! -d "$advertiser" ] &&
        [ "$(settings get global bluetooth_on)" = 1 ] && stack_has_override; then
        trap cleanup EXIT
        trap 'exit 1' HUP INT TERM
        echo 'Reusing previous pairing configuration.'
        wait_for_session
        exit 0
    fi
    echo 'Recovering previous pairing session.'
    restore_config
fi

if [ "$mode" = --recover ]; then exit 0; fi

if [ "$mode" = --app-static ] && ! static_supported; then
    echo 'STATIC_ADDRESS_FAILED'
    exit 1
fi

[ "$(settings get global bluetooth_on)" = 1 ] ||
    { echo 'BLUETOOTH_OFF'; exit 1; }
if in_namespace grep -Eq '^[[:space:]]*PTS_SmpOptions[[:space:]]*=' "$target" || has_config_mount; then
    echo 'EXISTING_SMP_OVERRIDE'
    exit 1
fi
[ "$(in_namespace grep -Ec "$stock_smp" "$target")" = 1 ] ||
    { echo 'UNSUPPORTED_SMP'; exit 1; }

original_hash="$(in_namespace sha256sum "$target" | cut -d ' ' -f 1)"
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
printf '%s\n' "$original_hash" > "$saved_hash"
in_namespace sed -E "s/$stock_smp.*/$expected/" "$target" > "$stage"
grep -Fxq "$expected" "$stage"
chmod 0644 "$stage"
chcon u:object_r:system_file:s0 "$stage"

restart=1
cmd bluetooth_manager disable
wait_for_bluetooth STATE_OFF
in_namespace mount -o bind "$stage" "$target"
mounted=1
cmd bluetooth_manager enable
wait_for_bluetooth STATE_ON
if ! stack_has_override; then
    echo 'The Bluetooth process cannot see the test configuration.' >&2
    exit 1
fi
wait_for_session
