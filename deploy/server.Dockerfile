# syntax=docker/dockerfile:1
# 在仓库根目录构建：docker build -f deploy/server.Dockerfile .
# 只需要 server/ 和 shared/，不需要 Android SDK（见 server.Dockerfile.dockerignore）。

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY shared/ shared/
COPY server/ server/
WORKDIR /src/server
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 qichi \
 && useradd --system --uid 10001 --gid qichi --home-dir /opt/qichi qichi \
 && mkdir -p /data/files \
 && chown qichi:qichi /data/files
COPY --from=build /src/server/build/install/qichi-server /opt/qichi
USER qichi
EXPOSE 8080
ENTRYPOINT ["/opt/qichi/bin/qichi-server"]
