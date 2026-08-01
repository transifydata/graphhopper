# GraphHopper on AWS ECS (us-east-2)

North America graph, 4 CH profiles (car w/ turn costs, transit_car, bike, walk),
served from `transify-config-aws.yml`. Not internet-accessible: internal ALB,
instances accept nothing but ALB -> 8989.

| Piece | What |
|---|---|
| Serving | ECS service `graphhopper-serve`, 1 task per **m8g.4xlarge** (16 vCPU / 64 GiB Graviton4), graph pulled from S3 at task start |
| Import | One-shot ECS task on an ephemeral **r8g.4xlarge spot** (128 GiB); ECS scales the ASG 0 -> 1 -> 0 automatically |
| Graph storage | `s3://graphhopper-graphs-140106082056/graphs/<version>/graph-cache.tar.zst`, `graphs/latest` pointer, elevation cache reused between imports |
| Infra | One CloudFormation stack `graphhopper` (`infra.yml`) — default VPC `vpc-38884c51`, existing `ecsInstanceRole` / `ecsTaskExecutionRole` |

## One-time setup

```bash
# 1. Create the infra (defaults for VPC/subnets/roles are baked in)
aws cloudformation deploy --region us-east-2 --stack-name graphhopper \
    --template-file aws/infra.yml --capabilities CAPABILITY_IAM

# 2. Build jar + push arm64 image, start the service
#    (serve tasks will log "no graph published yet" and wait — expected)
./aws/deploy-serve.sh

# 3. Build and publish the first graph, then roll the service onto it.
#    Takes hours (turn-cost CH prep for car dominates); Ctrl-C only stops the
#    polling, not the build.
./aws/build-graph.sh --deploy
```

Get the internal URL, then verify from any box inside the VPC:

```bash
aws cloudformation describe-stacks --region us-east-2 --stack-name graphhopper \
    --query "Stacks[0].Outputs[?OutputKey=='GraphhopperUrl'].OutputValue" --output text
curl "$GRAPHHOPPER_URL/health" && curl "$GRAPHHOPPER_URL/info"
```

## Ongoing operations

- **Deploy code**: `./aws/deploy-serve.sh` (`-n` skips the maven build). Rolling:
  the new task downloads the graph and must pass `/health` before the old stops.
- **Refresh the graph** (new OSM data): `./aws/build-graph.sh --deploy`
- **Rollback to an older graph**: redeploy the stack with
  `--parameter-overrides GraphVersionPin=<version>` (versions = prefixes under
  `graphs/` in the bucket). Empty pin follows `latest`.
- **Scale out / HA**: `--parameter-overrides ServeDesiredCount=2`
- **Logs**: `aws logs tail --region us-east-2 --follow /ecs/graphhopper-serve`
  (or `/ecs/graphhopper-import` during builds)

## Wiring up transify-transit-routing-api (Fargate, same VPC)

1. Read the base URL from the environment in `graphhopper_api.py`:
   ```python
   graphhopper_base_url = os.environ.get('GRAPHHOPPER_URL', 'http://localhost:8989')
   ```
2. In the Fargate service: set `GRAPHHOPPER_URL` to the `GraphhopperUrl` stack
   output, and attach the `ClientSecurityGroupId` stack output as one of the
   task's security groups — that SG is what the ALB admits.

Batch calls: `/batch-spt` sends no bytes until the whole computation finishes;
the ALB idle timeout is set to 3600s to accommodate that. If a network-wide
batch could exceed an hour, chunk the stops client-side (also caps response
size and server memory).

## Sizing notes

- **Serving memory is the one open question.** The first import logs
  `graph-cache is <size>`. If it approaches ~45 GB, move serving to 128 GiB:
  `--parameter-overrides ServeInstanceType=r8g.4xlarge ServeTaskMemoryMiB=120000
  ServeJavaOpts="-Xms100g -Xmx100g -XX:+UseZGC -XX:+ZGenerational"`.
- **If the import OOMs** (turn-cost CH for car on North America is the heavy
  part): `ImportInstanceType=r8g.8xlarge ImportTaskMemoryMiB=245760
  ImportJavaOpts="-Xms200g -Xmx200g -XX:+UseParallelGC"` (~$0.8/hr spot).
- A single `/batch-spt` request lands on one instance, so one city's batch gets
  faster with a bigger box (m8g.8xlarge), not more instances; more instances
  help concurrent batches.

## Cost (on-demand, us-east-2)

- Serving: 1 x m8g.4xlarge ~= $524/mo (+ ~$40/mo EBS/ALB); roughly 2/3 of that
  with a 1-yr Compute Savings Plan. A second task for HA doubles instance cost.
- Import: r8g.4xlarge on-demand ~= $0.94/hr, roughly $7-9 per graph build.
  (Spot is ~60% cheaper via ImportUseSpot=true, but r8g.4xlarge spot proved
  flaky in us-east-2 — a reclaim mid-import wastes the whole run.)
- S3: ~40-80 GB of graphs + elevation, single-digit $/mo. The default VPC
  already has an S3 gateway endpoint, so transfers are free and fast.

## Notes

- Instances have public IPs (default-VPC subnets) but no inbound rules besides
  ALB -> 8989, and the ALB is internal. For shell access use SSM
  (`aws ssm start-session`) after adding `AmazonSSMManagedInstanceCore` to
  `ecsInstanceRole`.
- Old graph versions accumulate in S3 (~40 GB each); prune manually or add a
  lifecycle rule once things are stable.
