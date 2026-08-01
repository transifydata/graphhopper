# Morning checklist (first deploy — 2026-07-29 overnight bootstrap)

Overnight, a detached script (`aws/first-deploy.log`) recreated the stack,
pushed the image, and launched the graph import on the ephemeral 128 GiB spot
instance. The import publishes to S3 and the instance terminates itself.

## 1. Did the bootstrap chain finish?

```bash
tail -5 aws/first-deploy.log        # want: "ALL LAUNCHED"
```

If it died early (e.g. laptop slept before the image push finished):

```bash
./aws/deploy-serve.sh -n            # push image (jar is already built)
./aws/build-graph.sh --deploy       # build graph + auto-start serving; polls for hours
```

## 2. Did the import succeed overnight?

```bash
aws s3 ls s3://graphhopper-graphs-140106082056/graphs/          # want a version + 'latest'
aws logs tail /ecs/graphhopper-import --region us-east-2 --since 15h --format short | tail -30
```

Note the `graph-cache is <size>` log line: if it approaches ~45 GB, serving
needs the r8g.4xlarge overrides (see aws/README.md "Sizing notes").

- **Import failed / spot reclaimed**: just rerun `./aws/build-graph.sh --deploy`
  (safe to Ctrl-C the polling; add `--parameter-overrides` sizing bumps first if
  it OOMed — see README).
- **Import still running**: fine, check again later; the instance self-terminates
  either way (managed scaling ~15 min after task stop, 20h import timeout, 24h
  MaxInstanceLifetime backstop).

## 3. Start serving (one command — raises desired count to 1 AND persists it)

```bash
aws cloudformation deploy --region us-east-2 --stack-name graphhopper \
    --template-file aws/infra.yml --capabilities CAPABILITY_IAM \
    --parameter-overrides ServeDesiredCount=1
```

First task start takes ~10 min (graph download + RAM load; health-check grace
covers it). Then:

```bash
aws cloudformation describe-stacks --region us-east-2 --stack-name graphhopper \
    --query "Stacks[0].Outputs" --output table   # note GraphhopperUrl + ClientSecurityGroupId
```

Verify from inside the VPC: `curl <GraphhopperUrl>/health` and `/info`.

## 4. Confirm the expensive box is gone

```bash
aws ec2 describe-instances --region us-east-2 \
    --filters Name=tag:Name,Values=graphhopper-import Name=instance-state-name,Values=running,pending \
    --query 'Reservations[].Instances[].InstanceId'    # want: empty
```

## 5. Wire up transify-transit-routing-api (when deploying it on Fargate)

- `graphhopper_api.py`: `graphhopper_base_url = os.environ.get('GRAPHHOPPER_URL', 'http://localhost:8989')`
- Fargate service: env `GRAPHHOPPER_URL=<GraphhopperUrl output>`, and attach
  `<ClientSecurityGroupId output>` as one of the task's security groups.

## 6. Commit the deployment kit

`aws/` + `.dockerignore` are uncommitted on branch `sync-upstream-11`.
(`aws/first-deploy.log` and this file can be deleted or committed as you like.)
