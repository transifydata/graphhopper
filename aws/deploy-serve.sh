#!/usr/bin/env bash
# Build the jar, push the arm64 image to ECR, and roll the ECS serving service.
# Usage: ./deploy-serve.sh [-n] [-p]
#   -n: skip the maven build (reuse existing web/target jar)
#   -p: push the image only, don't roll the serving service (use before
#       build-graph.sh when only configs/custom models changed: the import task
#       pulls :latest, and build-graph.sh --deploy rolls serving at the end)
set -euo pipefail
cd "$(dirname "$0")/.."

# Resources created by the 'graphhopper' CloudFormation stack (aws/infra.yml)
REGION=us-east-2
CLUSTER=graphhopper
ECR_URL=140106082056.dkr.ecr.us-east-2.amazonaws.com/graphhopper
SERVE_SERVICE=graphhopper-serve

no_build=false
push_only=false
while getopts ":np" opt; do
    case $opt in
        n) no_build=true ;;
        p) push_only=true ;;
        *) ;;
    esac
done

if [ "$no_build" = "true" ]; then
    echo "==> Skipping maven build"
else
    echo "==> Building jar"
    mvn clean package -pl web -am -DskipTests
fi

SHA=$(git rev-parse --short HEAD)
REGISTRY="${ECR_URL%%/*}"

echo "==> Pushing image to $ECR_URL (:latest, :$SHA)"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
docker buildx build --platform linux/arm64 -f aws/Dockerfile \
    -t "$ECR_URL:latest" -t "$ECR_URL:$SHA" --push .

if [ "$push_only" = "true" ]; then
    echo "==> Pushed $SHA (image only, service not rolled)"
    exit 0
fi

echo "==> Rolling service $SERVE_SERVICE"
aws ecs update-service --region "$REGION" --cluster "$CLUSTER" \
    --service "$SERVE_SERVICE" --force-new-deployment --query 'service.serviceName' --output text
aws ecs wait services-stable --region "$REGION" --cluster "$CLUSTER" --services "$SERVE_SERVICE"
echo "==> Deployed $SHA"
