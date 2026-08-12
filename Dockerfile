FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml ./
COPY gewu-common/pom.xml gewu-common/
COPY gewu-domain/pom.xml gewu-domain/
COPY gewu-infrastructure/pom.xml gewu-infrastructure/
COPY gewu-application/pom.xml gewu-application/
COPY gewu-interface/pom.xml gewu-interface/
COPY gewu-gateway/pom.xml gewu-gateway/
COPY gewu-sandbox/pom.xml gewu-sandbox/
RUN mvn dependency:go-offline -B
COPY . .
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/gewu-interface/target/gewu-interface-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-Xms2g -Xmx2g -Xmn1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:ParallelRefProcEnabled -XX:+ExplicitGCInvokesConcurrent -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
