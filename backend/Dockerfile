# The interface is served by the frontend image; this image holds the API and the solver.
# Build context is the repository root (the demo dataset lives in tests/fixtures/synthetic).

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline || true
COPY tests/fixtures/synthetic/demo-dataset-2027-01.synthetic.json /src/tests/fixtures/synthetic/
COPY backend/src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
# The application belongs to root, so the running user cannot change it.
COPY --from=build /src/backend/target/quarkus-app/ /app/
COPY LICENSE THIRD_PARTY.md /app/
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
