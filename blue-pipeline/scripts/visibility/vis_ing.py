#!/usr/bin/env bash
set -euo pipefail

echo "== Start stack =="
cd ~/Developer/git/eth-red-blue/blue-pipeline
docker compose up -d
docker compose ps

echo "== Local EVE proof (last 5 http/http2) =="
jq -c '
  select(.event_type=="http" or .event_type=="http2")
  | {ts:.timestamp,src:.src_ip,dst:.dest_ip,
     host:(.http.hostname // .http2.authority // ""),
     url:(.http.url // .http2.path // ""),
     ua:(.http.http_user_agent // .http2.http_user_agent // "")}
' < ~/Developer/git/eth-red-blue/blue-pipeline/suricata/log/eve.json | tail -n 5

echo "== ES count (last 2h) =="
curl -s 'http://localhost:9200/filebeat*,logs-*/_count' \
  -H 'Content-Type: application/json' -d '{
    "query":{"bool":{
      "must":[{"term":{"event.module":"suricata"}},
              {"terms":{"suricata.eve.event_type":["http","http2"]}}],
      "filter":[{"range":{"@timestamp":{"gte":"now-2h"}}}]
}}}' | jq

echo "== ES sample docs =="
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
