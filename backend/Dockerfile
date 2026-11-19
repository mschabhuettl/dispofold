# The interface is served by the frontend image; this image holds the API and the solver.
# Build context is the repository root (the demo dataset lives in tests/fixtures/synthetic).

# Maven repository to start from: empty here. CI and the release pass the runner's own as build context m2seed
# (--build-context m2seed=<dir>), so that the build asks Maven Central for (almost) nothing; Central limits the
# requests per address, and a cold build fetches the whole tree.
FROM scratch AS m2seed

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src/backend
COPY backend/pom.xml ./
COPY backend/.mvn/maven.config ./.mvn/
# The local repository lives in a build cache, not in the image; it starts with the seed.
RUN --mount=type=bind,from=m2seed,target=/seed --mount=type=cache,target=/root/.m2,sharing=locked \
    mkdir -p /root/.m2/repository && cp -a /seed/. /root/.m2/repository/
# maven.config retries transient errors of Maven Central and, with -U, what an earlier run did not find. The
# dependencies are fetched up to 3 times, because some errors are not retried within a run (a not found, a
# timeout); a missing artifact fails after the third attempt.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    for attempt in 1 2 3; do mvn -B -q dependency:go-offline && break; [ "$attempt" = 3 ] && exit 1; sleep 20; done
COPY tests/fixtures/synthetic/demo-dataset-2027-01.synthetic.json /src/tests/fixtures/synthetic/
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -q package -DskipTests

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
