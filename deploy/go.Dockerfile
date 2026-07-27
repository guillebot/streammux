# syntax=docker/dockerfile:1.6
# Go service container for streammux MCP. SERVICE=mcp builds cmd/mcp plus stmctl and healthcheck.
ARG SERVICE=mcp

FROM golang:1.25-alpine AS build
ARG SERVICE
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/app ./cmd/${SERVICE}
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/healthcheck ./cmd/healthcheck
RUN if [ "${SERVICE}" = "mcp" ]; then \
      CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/stmctl ./cmd/stmctl; \
    fi
RUN mkdir -p /out/data && chown -R 65532:65532 /out/data

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/app /app
COPY --from=build /out/healthcheck /healthcheck
COPY --from=build --chown=nonroot:nonroot /out/data /data
COPY --from=build /out/stmctl /stmctl
USER nonroot:nonroot
ENTRYPOINT ["/app"]
