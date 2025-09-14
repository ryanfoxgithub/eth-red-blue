#!/usr/bin/env bash
set -Eeuo pipefail

# ---------------- R O O T  &  E V I D E N C E  P A T H S ----------------
# Resolve repo root from this script's location (.../scripts -> ..)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"

EVID="$ROOT/evidence/3.2"
DEL="$EVID/delivery"
C2="$EVID/c2"
AOO="$EVID/aoo"
SID="$EVID/sideload"
PER="$EVID/persistence"
COR="$EVID/correlation"
mkdir -p "$DEL" "$C2" "$AOO" "$SID" "$PER" "$COR"

# ---------------- A D B  H E L P E R S  ----------------
# Tunables (override via env when calling the script)
ADB_DISCOVERY_TIMEOUT="${ADB_DISCOVERY_TIMEOUT:-150}"   # seconds to wait post-reboot
ADB_MODEL="${ADB_MODEL:-}"                               # optional mDNS model filter (e.g., "Pixel 6a")

# Wrapper: once we "adopt" a device, use ANDROID_SERIAL everywhere
ADB() { adb -s "$ANDROID_SERIAL" "$@"; }

# Current device list (skip emulators); prints: "serial state"
adb_list_devices() {
  adb devices -l | awk 'NR>1 && $1!="" && $1!="List" && $1 !~ /^emulator-/ { print $1, $2 }'
}

# Space-separated snapshot of serials currently known to adb
adb_snapshot_serials() {
  adb_list_devices | awk '{print $1}' | tr '\n' ' '
}

# Adopt a serial/ip:port for the rest of this run
adb_adopt() {
  export ANDROID_SERIAL="$1"
  echo "[ADB] Using device: $ANDROID_SERIAL"
}

# First "new" device (state=device) that wasn't in the snapshot
adb_find_first_new_device() {
  local snapshot="$1"; local s state
  while read -r s state; do
    [ "$state" = "device" ] || continue
    case " $snapshot " in
      *" $s "* ) : ;;            # already known -> skip
      * ) echo "$s"; return 0 ;;
    esac
  done < <(adb_list_devices)
  return 1
}

# Try mDNS, single present device, or USB as fallbacks to get *some* target
adb_try_fallbacks() {
  # If user provided a direct target, try it first
  if [ -n "${ADB_CONNECT:-}" ]; then
    adb connect "$ADB_CONNECT" >/dev/null 2>&1 || true
    if adb -s "$ADB_CONNECT" get-state >/dev/null 2>&1; then
      echo "$ADB_CONNECT"; return 0
    fi
  fi

  # Exactly one physical device now? take it.
  local count current
  count=$(adb_list_devices | awk '$2=="device"{print $1}' | wc -l | tr -d ' ')
  if [ "$count" = "1" ]; then
    current=$(adb_list_devices | awk '$2=="device"{print $1}')
    [ -n "$current" ] && { echo "$current"; return 0; }
  fi

  # mDNS (_adb-tls-connect) -> ip:port
  local line hostport
  line="$(adb mdns services 2>/dev/null | grep -i '_adb-tls-connect' | { [ -n "$ADB_MODEL" ] && grep -i "$ADB_MODEL" || cat; } | head -n1 || true)"
  hostport="$(printf '%s\n' "$line" | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{2,5}' | head -n1 || true)"
  if [ -n "$hostport" ]; then
    adb connect "$hostport" >/dev/null 2>&1 || true
    if adb -s "$hostport" get-state >/dev/null 2>&1; then
      echo "$hostport"; return 0
    fi
  fi

  # USB (-d)
  if adb -d get-state >/dev/null 2>&1; then
    adb -d get-serialno 2>/dev/null && return 0
  fi

  return 1
}

# Bind to any present device before we need ADB (prefer USB; else first device; else mDNS)
adb_bind_initial() {
  # USB first
  if adb -d get-state >/dev/null 2>&1; then
    adb_adopt "$(adb -d get-serialno)"; return 0
  fi
  # If exactly one active device, take it
  local count dev
  count=$(adb_list_devices | awk '$2=="device"{print $1}' | wc -l | tr -d ' ')
  if [ "$count" = "1" ]; then
    dev=$(adb_list_devices | awk '$2=="device"{print $1}')
    adb_adopt "$dev"; return 0
  fi
  # Fallbacks
  if dev="$(adb_try_fallbacks)"; then
    adb_adopt "$dev"; return 0
  fi
  echo "[ADB] No device found. Connect USB or run: adb connect <ip:port>" >&2
  return 1
}

# Reboot and attach to the first *new* device that appears; wait for full boot.
adb_reboot_and_bind_new() {
  local snapshot new deadline=$((SECONDS + ADB_DISCOVERY_TIMEOUT))
  snapshot="$(adb_snapshot_serials)"

  # Scope reboot to a single device if exactly one is present; otherwise generic reboot
  local current_count current_serial
  current_count=$(adb_list_devices | awk '$2=="device"{print $1}' | wc -l | tr -d ' ')
  if [ "$current_count" = "1" ]; then
    current_serial=$(adb_list_devices | awk '$2=="device"{print $1}')
    adb -s "$current_serial" reboot || true
  else
    adb reboot || true
  fi

  # Old wireless target is stale
  adb disconnect >/dev/null 2>&1 || true

  echo "[ADB] Waiting for a NEW device to appear (up to ${ADB_DISCOVERY_TIMEOUT}s)…"
  while [ $SECONDS -lt $deadline ]; do
    # If you manually `adb connect ip:port`, it will show here and be detected as NEW
    if new="$(adb_find_first_new_device "$snapshot")"; then
      adb_adopt "$new"; break
    fi
    # Try fallbacks (single device, mDNS, USB)
    if new="$(adb_try_fallbacks)"; then
      adb_adopt "$new"; break
    fi
    sleep 2
  done

  if [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "[ADB] No device found after reboot. Ensure Wireless debugging is on, then run: adb connect <ip:port>" >&2
    exit 1
  fi

  # Wait for transport + full boot
  adb wait-for-device
  local deadline2=$((SECONDS + 180))
  while [ $SECONDS -lt $deadline2 ]; do
    if [ "1" = "$(ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" ]; then
      sleep 5  # let Wi‑Fi + WorkManager settle
      return 0
    fi
    sleep 2
  done
  echo "[ADB] Timed out waiting for sys.boot_completed on $ANDROID_SERIAL" >&2
  exit 1
}

# ---------------- S U R I C A T A  /  W A Z U H  H E L P E R S ----------------
eve_tail() {
  # Prefer host paths if readable; else tail inside the container
  for f in "$ROOT/suricata/log/eve.json" "$ROOT/config/suricata/log/eve.json"; do
    if [ -r "$f" ]; then tail -n 2000 "$f"; return; fi
  done
  docker compose -f "$ROOT/docker-compose.yml" exec -T suricata sh -lc 'tail -n 2000 /var/log/suricata/eve.json'
}

wazuh_last_by_id() {
  local rule_id="$1" ; local n="${2:-20}"
  docker compose -f "$ROOT/docker-compose.yml" exec -T wazuh-manager sh -lc \
    "grep -F '\"id\":\"$rule_id\"' /var/ossec/logs/alerts/alerts.json | tail -n $n"
}

# ---------------- A P K  R E S O L U T I O N ----------------
APK="${APK:-}"
for cand in \
  "$HOME/Developer/git/eth-red-blue/apps/locker-sim-android/app/build/outputs/apk/debug/app-debug.apk" \
  "$ROOT/../apps/locker-sim-android/app/build/outputs/apk/debug/app-debug.apk" \
  "$ROOT/apps/locker-sim-android/app/build/outputs/apk/debug/app-debug.apk"
do
  if [ -z "$APK" ] && [ -f "$cand" ]; then APK="$cand"; fi
done
if [ -z "${APK:-}" ] || [ ! -f "$APK" ]; then
  echo "[APK] Not found. Build it or pass APK=/full/path/app-debug.apk" >&2
  echo "Hint: (cd \"$ROOT/../apps/locker-sim-android\" && ./gradlew assembleDebug)" >&2
  exit 1
fi

# ---------------- R U N ----------------
echo "Run @ $(date -Iseconds)" | tee -a "$DEL/run.txt" "$C2/run.txt" "$SID/run.txt" "$PER/run.txt" "$AOO/run.txt" "$COR/run.txt" >/dev/null

############  D1 — Delivery (APK GET)  ############
echo "[D1] Trigger GET /app-debug.apk"
curl -s -o /dev/null http://10.42.0.1:8000/app-debug.apk || true

echo "[D1] Capture last 3 alerts (sid 420010) to $DEL/d1_alert.json"
eve_tail | jq -c 'select(.event_type=="alert" and .alert.signature_id==420010)
  | {ts:.timestamp,src:.src_ip,dst:.dest_ip,method:.http.http_method,uri:.http.url,sid:.alert.signature_id,sig:.alert.signature}' \
  | tail -n 3 | tee -a "$DEL/d1_alert.json" >/dev/null

############  N1 — Beacon (POST + UA)  ############
echo "[N1] Trigger lab POST /beacon with UA"
curl -s -X POST http://10.42.0.1:8000/beacon \
  -H 'User-Agent: Locker-Beacon/1.0 (SIMULATION)' \
  -H 'Content-Type: application/json' \
  -d '{"lab":true,"ts":"'"$(date -Iseconds)"'"}' >/dev/null || true

echo "[N1] Capture last 3 alerts (sid 420001) to $C2/n1_alert.json"
eve_tail | jq -c 'select(.event_type=="alert" and .alert.signature_id==420001)
  | {ts:.timestamp,src:.src_ip,dst:.dest_ip,method:.http.http_method,uri:.http.url,ua:.http.http_user_agent,sid:.alert.signature_id,sig:.alert.signature}' \
  | tail -n 3 | tee -a "$C2/n1_alert.json" >/dev/null

############  W1/W2 — Sideload / install  ############
echo "[W1/W2] Uninstall + reinstall LockerSim to force PackageInstaller/Manager logs"
adb_bind_initial
ADB uninstall au.edu.deakin.lab.lockersim >/dev/null 2>&1 || true
ADB install -r "$APK"

echo "[W1/W2] Save last 20 matching Wazuh alerts (910110) to $SID/w1w2_alerts.json"
wazuh_last_by_id 910110 20 | tee -a "$SID/w1w2_alerts.json" >/dev/null

############  W3/W4 — Persistence signals (boot + worker)  ############
echo "[W3/W4] Reboot phone and wait for reconnect..."
adb_reboot_and_bind_new

echo "[W3] Save last 10 BootReceiver alerts (910120) to $PER/w3_boot.json"
wazuh_last_by_id 910120 10 | tee -a "$PER/w3_boot.json" >/dev/null

echo "[W4] Save last 10 BeaconWorker alerts (910130) to $C2/w4_alerts.json"
wazuh_last_by_id 910130 10 | tee -a "$C2/w4_alerts.json" >/dev/null

############  W5 — Actions on Objective (encrypt/decrypt)  ############
echo "[W5] Prompt: on the phone, seed files then ENCRYPT then DECRYPT in the app UI."
read -p "Press Enter AFTER you have tapped ENCRYPT and then DECRYPT on the device..."

echo "[W5] Save last 20 AoO alerts (910140) to $AOO/w5_alerts.json"
wazuh_last_by_id 910140 20 | tee -a "$AOO/w5_alerts.json" >/dev/null

echo "Done."
