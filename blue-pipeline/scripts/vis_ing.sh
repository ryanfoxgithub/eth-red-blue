#!/usr/bin/env bash
# Suricata -> Filebeat -> Elasticsearch visibility proof
# Saves reproducible artefacts for the report/video.
set -Eeuo pipefail
shopt -s lastpipe

# ---------- Config (override with env vars if needed) ----------
PIPELINE_DIR="${PIPELINE_DIR:-$HOME/Developer/git/eth-red-blue/blue-pipeline/config}"
EVE_PATH="${EVE_PATH:-$PIPELINE_DIR/suricata/log/eve.json}"
ES_URL="${ES_URL:-http://localhost:9200}"
INDEX_PATTERN="${INDEX_PATTERN:-filebeat*,logs-*}"
EVID_DIR="${EVID_DIR:-$PIPELINE_DIR/evidence/ingest-$(date +%F_%H%M%S)}"

# ---------- Sanity checks ----------
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1"; exit 1; }; }
need docker; need jq; need curl
mkdir -p "$EVID_DIR"

echo "== Start stack =="
cd "$PIPELINE_DIR"
docker compose up -d
docker compose ps | tee "$EVID_DIR/compose_ps.txt"

# Optional: wait briefly for first EVE rows / Filebeat flush
echo "== Waiting up to 20s for EVE writes =="
for i in {1..20}; do
  if [ -s "$EVE_PATH" ]; then break; fi
  sleep 1
done

echo "== Local EVE (last 5 http/http2) =="
jq -c '
  select(.event_type=="http" or .event_type=="http2")
  | {ts:.timestamp,src:.src_ip,dst:.dest_ip,
     host:(.http.hostname // .http2.authority // ""),
     url:(.http.url // .http2.path // ""),
     ua:(.http.http_user_agent // .http2.http_user_agent // "")}
' < "$EVE_PATH" | tail -n 5 | tee "$EVID_DIR/eve_sample.json"

echo "== ES count (last 2h) =="
curl -s "$ES_URL/$INDEX_PATTERN/_count" \
  -H 'Content-Type: application/json' -d '{
    "query":{"bool":{
      "must":[{"term":{"event.module":"suricata"}},
              {"terms":{"suricata.eve.event_type":["http","http2"]}}],
      "filter":[{"range":{"@timestamp":{"gte":"now-2h"}}}]
}}}' | tee "$EVID_DIR/es_count.json" | jq

echo "== ES sample docs =="
curl -s "$ES_URL/$INDEX_PATTERN/_search" \
  -H 'Content-Type: application/json' -d '{
    "size": 3, "sort":[{"@timestamp":{"order":"desc"}}],
    "_source": ["@timestamp","source.ip","destination.ip",
                "suricata.eve.http.hostname","suricata.eve.http.url",
                "suricata.eve.http.http_method","user_agent.original"],
    "query":{"bool":{
      "must":[{"term":{"event.module":"suricata"}},
              {"terms":{"suricata.eve.event_type":["http","http2"]}}]
}}}' | jq '.hits.hits[]._source' | tee "$EVID_DIR/es_docs.json"

echo "Evidence saved to: $EVID_DIR"
