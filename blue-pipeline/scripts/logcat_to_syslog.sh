#!/usr/bin/env bash
set -Eeuo pipefail
adb logcat -v threadtime -s \
  BootReceiver,BeaconWorker,PackageInstaller,PackageManager,LockerSim \
| stdbuf -oL logger -t pixel6a
