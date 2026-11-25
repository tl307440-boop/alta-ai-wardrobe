# Stage 1: Build Spring Boot executable JAR (runs on local dev machine or GitHub Actions, never on 2GB ECS)
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Stage 2: Low-memory Alpine JRE 21 runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache tzdata wget \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && mkdir -p /app/uploads /app/data

COPY --from=builder /build/target/ai-wardrobe-backend-*.jar /app/app.jar

ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=mysql \
    APP_UPLOAD_DIR=/app/uploads \
    JAVA_OPTS="-XX:+UseSerialGC -Xms64m -Xmx224m -XX:MaxMetaspaceSize=112m -Xss512k -XX:MinHeapFreeRatio=15 -XX:MaxHeapFreeRatio=35 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
