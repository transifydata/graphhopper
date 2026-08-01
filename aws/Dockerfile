# arm64 image for Graviton (m8g/r8g). Build with:
#   docker buildx build --platform linux/arm64 -f aws/Dockerfile -t <ecr>:latest --push .
# The jar is built on the host first (see aws/deploy-serve.sh) since it is
# architecture-independent.
FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates zstd unzip \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip" -o /tmp/awscli.zip \
    && unzip -q /tmp/awscli.zip -d /tmp \
    && /tmp/aws/install \
    && rm -rf /tmp/aws /tmp/awscli.zip

COPY web/target/graphhopper-web-*.jar /gh/graphhopper-web.jar
COPY transify-config-aws.yml /gh/config.yml
COPY transify-config-aws-fast.yml /gh/config-fast.yml
COPY custom_models/ /gh/custom_models/
COPY aws/docker-entrypoint.sh /gh/entrypoint.sh
RUN chmod +x /gh/entrypoint.sh

# /data is a bind mount from the host EBS volume: pbf, elevation cache, graph-cache, logs.
WORKDIR /data
ENTRYPOINT ["/gh/entrypoint.sh"]
