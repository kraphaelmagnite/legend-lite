#!/bin/bash
# Records legend-engine's own answers to the model writer's files (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15.7):
# the project ModelWriterUpstreamTest pins (one-go.pure) compiled, and each query it lists (queries.pure) planned,
# written to engine-answers.json beside them. Run by hand against a legend-engine 4.145.0 server, after the pin moves:
#
#   docs/datacube-model-homework-2026-10-11/record-upstream.sh [engine base URL, default http://127.0.0.1:6300]
#
# Needs curl, jq and shasum on PATH; nothing here runs in a build. The answers carry each pinned file's SHA-256, so the
# test knows they are the answers to the files it pins.
set -euo pipefail

ENGINE="${1:-http://127.0.0.1:6300}"
DIR="$(cd "$(dirname "$0")/../../core/src/test/resources/upstream-api/model-writer" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

post() { # path, content type, body file -> the answer in $WORK/answer, its status printed
  curl -sS -m 300 -o "$WORK/answer" -w '%{http_code}' -H "Content-Type: $2" --data-binary "@$3" "$ENGINE$1"
}

version="$(curl -sS -m 30 "$ENGINE/api/server/v1/info" | jq -r '.info.legendSDLC["git.build.version"]')"

jq -Rs '{_type: "text", code: .}' "$DIR/one-go.pure" > "$WORK/compile.json"
status="$(post /api/pure/v1/compilation/compile application/json "$WORK/compile.json")"
jq -n --argjson status "$status" --slurpfile answer "$WORK/answer" '{status: $status, answer: $answer[0]}' \
  > "$WORK/compiled.json"

: > "$WORK/plans.jsonl"
while IFS= read -r query; do
  [ -n "$query" ] || continue
  printf '%s' "$query" > "$WORK/query.pure"
  post '/api/pure/v1/grammar/grammarToJson/lambda?returnSourceInformation=false' text/plain "$WORK/query.pure" > /dev/null
  cp "$WORK/answer" "$WORK/lambda.json"
  jq -n --slurpfile f "$WORK/lambda.json" --rawfile code "$DIR/one-go.pure" \
    '{clientVersion: "vX_X_X", function: $f[0], model: {_type: "text", code: $code},
      context: {_type: "BaseExecutionContext"}}' > "$WORK/plan.json"
  status="$(post /api/pure/v1/execution/generatePlan application/json "$WORK/plan.json")"
  # the plan's SQL, or the engine's message when it planned nothing
  jq -c --arg query "$query" --argjson status "$status" \
    '{query: $query, status: $status, sql: ([.. | .sqlQuery? // empty] | first // ""), message: (.message // "")}' \
    "$WORK/answer" >> "$WORK/plans.jsonl"
done < "$DIR/queries.pure"

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
jq -n --arg engine "$version" --arg project "$(sha "$DIR/one-go.pure")" --arg queries "$(sha "$DIR/queries.pure")" \
  --slurpfile compiled "$WORK/compiled.json" --slurpfile plans "$WORK/plans.jsonl" \
  '{engine: $engine, pinned: {"one-go.pure": $project, "queries.pure": $queries}, compile: $compiled[0], plans: $plans}' \
  > "$DIR/engine-answers.json"
echo "recorded $DIR/engine-answers.json (legend-engine $version)"
