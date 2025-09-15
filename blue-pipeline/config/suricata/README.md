# Suricata config

- **Config:** `suricata.yaml` (EVE --> JSON; enable `http`, `http2`, `tls`, `dns`, `flow`, `alert`; set `HOME_NET: [10.42.0.0/24]`).
- **Rules:** `rules/local.rules` (mounted into the container).

## Custom rules
**D1 – APK delivery**
alert http $HOME_NET any -> $HOME_NET 8000 (
msg:"APK download to handset (lab host)";
flow:to_server,established;
http.method; content:"GET"; nocase;
http.uri; content:"/app-debug.apk"; nocase;
classtype:policy-violation;
sid:420010; rev:3;
)

**N1 - Locker beacon**
alert http $HOME_NET any -> $HOME_NET any (
msg:"LockerSim HTTP beacon (UA+path)";
flow:to_server,established;
http.method; content:"POST"; nocase;
http.uri; content:"/beacon";
http.user_agent; content:"Locker-Beacon/1.0";
classtype:trojan-activity;
sid:420001; rev:7;
)

**Verify:** `docker compose exec suricata suricata -T`  
**EVE path:** host‑mounted under `suricata/` (see `docker-compose.yml`).

