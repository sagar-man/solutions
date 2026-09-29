#!/usr/bin/env bash
#
# Capture the access data the Access Audit engine needs, straight off a device
# over adb, without installing anything on the phone.
#
# Everything here is read-only: it runs `dumpsys` and `pm`, and writes only to
# the output directory on your machine.
#
# Usage:
#   tools/adb-access-audit.sh [-s SERIAL] [-o OUTDIR]
#   ./gradlew :cli:run --args="--dir OUTDIR --sdk 34"
#
set -euo pipefail

SERIAL=""
OUTDIR=""

usage() {
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//; /^set -euo/d'
    exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial) SERIAL="${2:?-s needs a device serial}"; shift 2 ;;
        -o|--out)    OUTDIR="${2:?-o needs a directory}"; shift 2 ;;
        -h|--help)   usage 0 ;;
        *)           echo "unknown argument: $1" >&2; usage 1 ;;
    esac
done

command -v adb >/dev/null 2>&1 || {
    echo "error: adb is not on PATH. Install platform-tools first." >&2
    exit 1
}

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

# Fail early with a useful message rather than writing empty capture files.
device_state="$("${ADB[@]}" get-state 2>/dev/null || true)"
if [[ "$device_state" != "device" ]]; then
    echo "error: no device ready over adb (state: ${device_state:-none})." >&2
    echo "       Check 'adb devices', and confirm USB debugging is authorised." >&2
    exit 1
fi

if [[ -z "$OUTDIR" ]]; then
    OUTDIR="access-audit-capture-$(date +%Y%m%d-%H%M%S)"
fi
mkdir -p "$OUTDIR"

sdk="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r\n')"
release="$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r\n')"
model="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r\n')"

echo "Device: $model  Android $release (SDK $sdk)"
echo "Capturing into: $OUTDIR"

capture() {
    local name="$1"; shift
    printf '  %-22s' "$name"
    # A failing dumpsys section must not abort the capture: a partial capture is
    # still analysable, and the engine reports which sources it actually got.
    if "${ADB[@]}" shell "$@" > "$OUTDIR/$name" 2>"$OUTDIR/$name.err"; then
        local lines
        lines="$(wc -l < "$OUTDIR/$name" | tr -d ' ')"
        rm -f "$OUTDIR/$name.err"
        echo "ok (${lines} lines)"
    else
        echo "FAILED (see $name.err)"
    fi
}

# The two the analyser needs.
capture packages.txt   'dumpsys package packages'
capture appops.txt     'dumpsys appops'

# Useful context, not required by the analyser.
capture usagestats.txt 'dumpsys usagestats'
capture packagelist.txt 'pm list packages -f -i -U'

# Record the capture time and SDK so the analyser can resolve the relative ages
# that `dumpsys appops` prints, instead of guessing from the current clock.
now_ms="$(( $(date +%s) * 1000 ))"
cat > "$OUTDIR/capture-info.txt" <<INFO
capturedAtMs=$now_ms
sdk=$sdk
release=$release
model=$model
INFO

if [[ ! -s "$OUTDIR/appops.txt" ]]; then
    cat >&2 <<'WARN'

warning: appops.txt is empty. Without it the report can only show which
         permissions each app holds, not which it actually used. On most
         devices `dumpsys appops` needs no extra setup, so check that the
         shell user is not restricted (some vendor builds lock it down).
WARN
fi

cat <<NEXT

Captured. Analyse it with:

  ./gradlew :cli:installDist
  ./cli/build/install/access-audit/bin/access-audit \\
      --dir "$OUTDIR" --sdk "$sdk" --now-ms "$now_ms"

Other formats: --format json | csv | matrix
Single app:    --package com.example.app
NEXT
