# Blue pipeline scripts

## vis_ing.sh - ingest visibility harness
**Purpose:** Sanity‑check Filebeat→Elasticsearch/Kibana ingest and write receipts.  
**Run:** `./scripts/vis_ing.sh`  
**Outputs:** `config/evidence/ingest-<timestamp>/{compose_ps.txt,es_count.json,es_docs.json,eve_sample.json}`  
**Notes:** Requires Docker/Compose and a running stack.

## logcat_to_syslog.sh - stream handset logs to Wazuh
**Purpose:** Stream filtered `adb logcat` to host syslog with program tag `pixel6a`.  
**Run (USB):** `./scripts/logcat_to_syslog.sh` (ensure `adb -d devices` shows your phone)  
**Run (Wi‑Fi):** `adb connect <ip:port>` then run the script.  
**Dependencies:** Android platform‑tools (`adb`), `logger`; Wazuh agent must monitor `/var/log/syslog`.  
**Emits:** Wazuh rules 910110/910120/910130/910140 in `config/wazuh/manager/local_rules.xml`.

## make_evidence_3_2.sh - detection evidence harness
**Purpose:** Automates D1, N1, W1–W5 collection; reboots device and waits for ADB re‑attach.  
**Run:** `./scripts/make_evidence_3_2.sh`  
**Outputs:** JSON lines appended under `evidence/3.2/*` (delivery, c2, sideload, persistence, aoo).  
**Resilience:** Handles wireless ADB churn; falls back to `adb -d` if present.
