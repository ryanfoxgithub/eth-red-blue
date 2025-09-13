#!/usr/bin/env bash
# Purpose: Prove end‑to‑end visibility for the lab:
#          Suricata (EVE JSON) -> Filebeat (Suricata module) -> Elasticsearch -> Kibana.
# How it proves it:
#   1) Starts the container stack and shows status
#   2) Reads recent HTTP/HTTP2 events directly from EVE JSON with jq
#   3) Counts + samples those same events from Elasticsearch
#
# Prereqs:
#   - bash, docker, docker compose
#   - jq and curl installed on the host
#   - Suricata container writes /var/log/suricata/eve.json, bind‑mounted to the host path below
#
# Safety: 'set -euo pipefail' makes the script exit on the first error, on unset vars,
#         and if any command in a pipeline fails.

set -euo pipefail

echo "== Start stack =="
# Move into the project where docker-compose.yml lives
cd ~/Developer/git/eth-red-blue/blue-pipeline

# Start the four containers (suricata, filebeat, elasticsearch, kibana) in the background
docker compose up -d

# Show current status so the viewer can see everything is 'Up/Running'
docker compose ps

echo "== Local EVE proof (last 5 http/http2) =="
# Read Suricata's EVE JSON and filter only HTTP/HTTP2 events.
# - select(.event_type=="http" or .event_type=="http2"): keep only web events
# - The object below picks key fields for readability:
#     ts  = event timestamp
#     src = source IP
#     dst = destination IP
#     host/url/user-agent: coalesce across HTTP/1.1 and HTTP/2 structures
# - tail -n 5: show just the last five events to prove fresh ingestion
jq -c '
  select(.event_type=="http" or .event_type=="http2")
  | {ts:.timestamp,src:.src_ip,dst:.dest_ip,
     host:(.http.hostname // .http2.authority // ""),
     url:(.http.url // .http2.path // ""),
     ua:(.http.http_user_agent // .http2.http_user_agent // "")}
' < ~/Developer/git/eth-red-blue/blue-pipeline/suricata/log/eve.json | tail -n 5

echo "== ES count (last 2h) =="
# Ask Elasticsearch to count Suricata web events in the last 2 hours.
# - Index pattern: filebeat*,logs-* covers the Filebeat data stream + legacy patterns
# - must: event.module == "suricata" AND event_type in ["http","http2"]
# - filter: @timestamp >= now-2h
# Expected: a non-zero "count" proves Filebeat is shipping EVE into Elasticsearch.
curl -s 'http://localhost:9200/filebeat*,logs-*/_count' \
  -H 'Content-Type: application/json' -d '{
    "query":{"bool":{
      "must":[{"term":{"event.module":"suricata"}},
              {"terms":{"suricata.eve.event_type":["http","http2"]}}],
      "filter":[{"range":{"@timestamp":{"gte":"now-2h"}}}]
}}}' | jq

echo "== ES sample docs =="
# Fetch a few of the most recent documents to show the same fields are indexed.
# - size: 3 (return three hits)
# - sort: newest first
# - _source: restrict output to fields we actually care about in the demo
# - jq: print only the _source objects for clarity
# These fields mirror what I’ll display in Kibana (Discover columns).
curl -s 'http://localhost:9200/filebeat*,logs-*/_search' \
  -H 'Content-Type: application/json' -d '{
    "size": 3, "sort":[{"@timestamp":{"order":"desc"}}],
    "_source": ["@timestamp","source.ip","destination.ip",
                "suricata.eve.http.hostname","suricata.eve.http.url",
                "suricata.eve.http.http_method","user_agent.original"],
    "query":{"bool":{
      "must":[{"term":{"event.module":"suricata"}},
              {"terms":{"suricata.eve.event_type":["http","http2"]}}]
}}}' | jq '.hits.hits[]._source' | tee ~/Developer/git/eth-red-blue/blue-pipeline/evidence/sample_docs.txt
# ^ tee: also save the pretty-printed sample to evidence/sample_docs.txt for the report
