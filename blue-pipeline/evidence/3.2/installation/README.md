# 3.2.7 Actions on Objective (Encrypt/Decrypt)

**Goal.** Detect AoO via app log tags indicating `ENCRYPT`/`DECRYPT` operations.

## Detection content
- **W5** --> Wazuh rule id **910140** (LockerSim encrypt/decrypt).

## Test I’ll run
1. Run the AoO scenario and recovery (2.7).
2. Confirm Wazuh W5 alert and review file paths in `full_log`.

## Expected artefacts
- Alert with `full_log` lines containing `LockerSim: ENCRYPT` or `DECRYPT`.

## Notes
- Timestamp for this package: **2025-09-14 18:27:23Z**.
