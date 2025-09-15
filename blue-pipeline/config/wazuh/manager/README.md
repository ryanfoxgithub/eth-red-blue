# Wazuh rules (local_rules.xml)

- **910110** Android sideload/install (PackageInstaller/PackageManager).
- **910120** Persistence: `BootReceiver` handled `BOOT_COMPLETED`.
- **910130** C2 worker executed (`BeaconWorker`/`WorkManager` lines).
- **910140** Actions on Objective: Encrypt/Decrypt activity (`LockerSim`/`FileRepo`).

**Input path:** Host streams `adb logcat` to syslog via `scripts/logcat_to_syslog.sh` (program name: `pixel6a`).  
**Watch:** `/var/ossec/logs/alerts/alerts.json` (or Kibana if shipping to ES).  
**Restart manager:** `docker compose exec wazuh-manager /var/ossec/bin/wazuh-control restart`.
