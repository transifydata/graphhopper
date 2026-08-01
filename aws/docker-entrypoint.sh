#!/usr/bin/env bash
# Entrypoint for both container roles, selected via MODE:
#   MODE=import  download PBF + elevation cache, build graph-cache, publish to S3, exit.
#   MODE=serve   pull latest (or pinned) graph-cache from S3, start the server.
set -euo pipefail

MODE="${MODE:?set MODE=import or MODE=serve}"
S3_BUCKET="${S3_BUCKET:?set S3_BUCKET}"
PBF_URL="${PBF_URL:-https://download.geofabrik.de/north-america-latest.osm.pbf}"
JAVA_OPTS="${JAVA_OPTS:--Xms8g -Xmx8g}"

DATA=/data
JAR=/gh/graphhopper-web.jar
# GH_CONFIG picks the baked-in config (config.yml = full, config-fast.yml =
# interim transit_car+walk). Import mode stamps the name next to the graph in
# S3; serve mode overrides this below with whatever the live graph was built with.
CONFIG="/gh/${GH_CONFIG:-config.yml}"
GRAPH_DIR="$DATA/graph-cache"
PBF_FILE="$DATA/$(basename "$PBF_URL")"

cd "$DATA"
mkdir -p logs elevation

# The config uses paths relative to the old GCP layout; pin everything to /data here.
SYS_PROPS=(
    "-Ddw.graphhopper.datareader.file=$PBF_FILE"
    "-Ddw.graphhopper.graph.location=$GRAPH_DIR"
    "-Ddw.graphhopper.graph.elevation.cache_dir=$DATA/elevation/"
    "-Ddw.graphhopper.custom_models.directory=/gh/custom_models"
)

case "$MODE" in

import)
    echo "==> Syncing elevation cache from s3://$S3_BUCKET/elevation (empty on first run)"
    aws s3 sync "s3://$S3_BUCKET/elevation" elevation/ --no-progress || true

    echo "==> Downloading $PBF_URL"
    # --speed-*: abort (and retry) if the transfer stalls below 1 KB/s for 5 min
    curl -fL --retry 5 --retry-delay 30 -C - --speed-limit 1000 --speed-time 300 \
        -o "$PBF_FILE" "$PBF_URL"

    echo "==> Importing (JAVA_OPTS=$JAVA_OPTS)"
    rm -rf "$GRAPH_DIR"
    # timeout guards against a hung import keeping the 128 GiB instance alive:
    # on expiry the container exits non-zero, the task stops, and ECS managed
    # scaling terminates the instance.
    # shellcheck disable=SC2086
    timeout "${IMPORT_TIMEOUT_SECONDS:-72000}" \
        java $JAVA_OPTS "${SYS_PROPS[@]}" -jar "$JAR" import "$CONFIG"

    VERSION="$(date -u +%Y%m%d-%H%M%S)"
    SIZE_BYTES="$(du -sb graph-cache | cut -f1)"
    echo "==> Import done, graph-cache is $(du -sh graph-cache | cut -f1); publishing as $VERSION"

    tar -C "$DATA" -cf - graph-cache | zstd -T0 -3 \
        | aws s3 cp - "s3://$S3_BUCKET/graphs/$VERSION/graph-cache.tar.zst" \
              --expected-size "$SIZE_BYTES" --no-progress

    echo "==> Saving elevation cache back to S3"
    aws s3 sync elevation/ "s3://$S3_BUCKET/elevation" --no-progress

    echo -n "${GH_CONFIG:-config.yml}" | aws s3 cp - "s3://$S3_BUCKET/graphs/$VERSION/config"
    echo -n "$VERSION" | aws s3 cp - "s3://$S3_BUCKET/graphs/latest"
    echo "==> Published s3://$S3_BUCKET/graphs/$VERSION (latest -> $VERSION)"
    ;;

serve)
    # GRAPH_VERSION env var pins a specific build (rollback); empty means follow 'latest'.
    VERSION="${GRAPH_VERSION:-}"
    while [ -z "$VERSION" ]; do
        VERSION="$(aws s3 cp "s3://$S3_BUCKET/graphs/latest" - 2>/dev/null || true)"
        if [ -z "$VERSION" ]; then
            echo "==> No graph published at s3://$S3_BUCKET/graphs/latest yet; retrying in 60s"
            sleep 60
        fi
    done

    # Serve with the config this graph was built with (stamped by import mode).
    CFG_NAME="$(aws s3 cp "s3://$S3_BUCKET/graphs/$VERSION/config" - 2>/dev/null || echo config.yml)"
    CONFIG="/gh/$CFG_NAME"
    echo "==> Graph $VERSION was built with $CFG_NAME"

    if [ "$(cat "$DATA/graph-cache.version" 2>/dev/null || true)" != "$VERSION" ]; then
        echo "==> Downloading graph $VERSION from S3"
        rm -rf "$GRAPH_DIR" "$DATA/graph-cache.version"
        aws s3 cp "s3://$S3_BUCKET/graphs/$VERSION/graph-cache.tar.zst" - --no-progress \
            | zstd -dc -T0 | tar -xf - -C "$DATA"
        echo -n "$VERSION" > "$DATA/graph-cache.version"
    else
        echo "==> Graph $VERSION already on disk, reusing"
    fi

    echo "==> Starting server with graph $VERSION (JAVA_OPTS=$JAVA_OPTS)"
    # shellcheck disable=SC2086
    exec java $JAVA_OPTS "${SYS_PROPS[@]}" -jar "$JAR" server "$CONFIG"
    ;;

*)
    echo "Unknown MODE '$MODE'" >&2
    exit 1
    ;;
esac
