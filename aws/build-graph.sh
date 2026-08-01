#!/usr/bin/env bash
# Builds the graph on a one-time 128 GiB (spot) instance and publishes it to S3.
#
# Runs the import task via the 'import' ECS capacity provider: ECS scales its ASG
# 0 -> 1, the r8g.4xlarge boots, runs the import container, uploads graph-cache to
# S3, and after the task exits ECS scales the ASG back to 0 (instance terminated).
#
# Usage: ./build-graph.sh [--deploy] [--config <name>]
#   --deploy: after a successful build, roll the serving service onto the new graph.
#   --config: baked-in config to build with (default config.yml = full build;
#             config-fast.yml = interim transit_car+walk, no elevation, ~1h).
#             Serving always follows the config stamped on the live graph.
#
# The import takes hours (turn-cost CH prep for 'car' dominates). Ctrl-C only stops
# this script's polling — the task keeps running; re-check with:
#   aws ecs list-tasks --cluster <cluster> --family <import task family>
set -euo pipefail
cd "$(dirname "$0")"

# Resources created by the 'graphhopper' CloudFormation stack (aws/infra.yml)
REGION=us-east-2
CLUSTER=graphhopper
S3_BUCKET=graphhopper-graphs-140106082056
SERVE_SERVICE=graphhopper-serve
IMPORT_TASK_DEF=graphhopper-import
IMPORT_CP=graphhopper-import-cp

DEPLOY=false
GH_CONFIG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --deploy) DEPLOY=true ;;
        --config) GH_CONFIG="$2"; shift ;;
        *) echo "unknown argument: $1" >&2; exit 1 ;;
    esac
    shift
done

OVERRIDES=()
if [ -n "$GH_CONFIG" ]; then
    OVERRIDES=(--overrides "{\"containerOverrides\":[{\"name\":\"graphhopper\",\"environment\":[{\"name\":\"GH_CONFIG\",\"value\":\"$GH_CONFIG\"}]}]}")
fi

echo "==> Launching import task on cluster $CLUSTER (capacity provider $IMPORT_CP, config ${GH_CONFIG:-config.yml})"
TASK_ARN=$(aws ecs run-task \
    --region "$REGION" \
    --cluster "$CLUSTER" \
    --task-definition "$IMPORT_TASK_DEF" \
    --capacity-provider-strategy "capacityProvider=$IMPORT_CP,weight=1" \
    --count 1 \
    ${OVERRIDES[@]+"${OVERRIDES[@]}"} \
    --query 'tasks[0].taskArn' --output text)
echo "    task: $TASK_ARN"
echo "    logs: aws logs tail --region $REGION --follow /ecs/$IMPORT_TASK_DEF"

START=$(date +%s)
LAST=""
while true; do
    STATUS=$(aws ecs describe-tasks --region "$REGION" --cluster "$CLUSTER" \
        --tasks "$TASK_ARN" --query 'tasks[0].lastStatus' --output text)
    [ "$STATUS" != "$LAST" ] && echo "==> $(date +%H:%M:%S) task status: $STATUS" && LAST="$STATUS"
    [ "$STATUS" = "STOPPED" ] && break
    sleep 60
done

EXIT_CODE=$(aws ecs describe-tasks --region "$REGION" --cluster "$CLUSTER" \
    --tasks "$TASK_ARN" --query 'tasks[0].containers[0].exitCode' --output text)
REASON=$(aws ecs describe-tasks --region "$REGION" --cluster "$CLUSTER" \
    --tasks "$TASK_ARN" --query 'tasks[0].stoppedReason' --output text)
ELAPSED=$(( ($(date +%s) - START) / 60 ))

if [ "$EXIT_CODE" != "0" ]; then
    echo "==> Import FAILED after ${ELAPSED}m (exit=$EXIT_CODE, reason: $REASON)" >&2
    exit 1
fi

VERSION=$(aws s3 cp "s3://$S3_BUCKET/graphs/latest" - --region "$REGION")
echo "==> Import succeeded in ${ELAPSED}m; published graph version: $VERSION"

if $DEPLOY; then
    echo "==> Rolling serving service $SERVE_SERVICE onto the new graph"
    # --desired-count 1: the service is created with 0 tasks (see infra.yml,
    # ServeDesiredCount); the first --deploy brings it up, later ones just roll it
    aws ecs update-service --region "$REGION" --cluster "$CLUSTER" \
        --service "$SERVE_SERVICE" --desired-count 1 \
        --force-new-deployment --query 'service.serviceName' --output text
    echo "==> Waiting for service to stabilize (new task must download + load the graph)"
    aws ecs wait services-stable --region "$REGION" --cluster "$CLUSTER" --services "$SERVE_SERVICE"
    echo "==> Deployed."
fi
