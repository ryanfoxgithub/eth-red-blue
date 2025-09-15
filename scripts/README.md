# Delivery & beacon helper tools

## beacon_server.py - APK host + beacon collector
**Run:** `python3 beacon_server.py --apk <path-to>/app-debug.apk --port 8000 --log beacons.jsonl`  
**Routes:** 
- `GET /app-debug.apk` --> serves APK (with `Content-Disposition: attachment`)
- `POST /beacon` --> appends JSON line to `beacons.jsonl`
**Notes:** Handles client disconnects gracefully; great for D1/N1 testing.

## qr_gen.py - QR generator for APK URL
**Install:** `pip install segno`  
**Run:** `python3 qr_gen.py --url http://10.42.0.1:8000/app-debug.apk --output qr.png --ascii`  
**Tip:** Keep to http(s) URLs only (tool enforces scheme).
