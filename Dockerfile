# syntax=docker/dockerfile:1.7

FROM ubuntu:24.04 AS native-build
RUN apt-get update \
    && apt-get install -y --no-install-recommends cmake g++ make \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY native/ native/
RUN cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build native/build --target hh-vm

FROM eclipse-temurin:17-jdk-jammy AS parser-build
WORKDIR /src
COPY colony-dsl-parser/ colony-dsl-parser/
RUN chmod +x colony-dsl-parser/gradlew \
    && ./colony-dsl-parser/gradlew -p colony-dsl-parser --no-daemon installDist

FROM node:20-bookworm AS frontend-build
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:17-jre-noble AS backend
WORKDIR /app
COPY --from=native-build /src/native/build/hh-vm /app/native/hh-vm
COPY --from=parser-build /src/colony-dsl-parser/build/install/colony-dsl-parser /app/colony-dsl-parser
COPY examples/ /app/examples/
ENV HH_VM=/app/native/hh-vm
EXPOSE 8080
ENTRYPOINT ["/app/colony-dsl-parser/bin/colony-dsl-parser"]

FROM frontend-build AS frontend
WORKDIR /src/frontend
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
