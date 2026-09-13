# ---------------------------------------------------------------------------
# 构建阶段：JDK 17 + sbt 1.10
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build

ARG SBT_VERSION=1.10.7
RUN apt-get update -qq && apt-get install -y -qq curl ca-certificates \
    && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
       | tar xz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /build

# 先拷贝构建定义，利用 Docker 层缓存预拉依赖
COPY build.sbt .
COPY project/ project/
RUN sbt update

COPY . .
RUN sbt clean stage

# ---------------------------------------------------------------------------
# 运行阶段
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /build/target/universal/stage/ ./

EXPOSE 9000
# 密钥等配置经环境变量注入（见 docker-compose.yml 与 application.conf 的 ${?VAR} 回退）
ENTRYPOINT ["bin/corpus-review-backend"]
