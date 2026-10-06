FROM golang@sha256:8ac98ca534ac3f51e1f420a1dd2c15e74c75cfa0f23f3ad27eb5d7236c349a0c AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build CGO_ENABLED=0 GOFLAGS=-mod=readonly go build -trimpath -o /dootah-server ./cmd/dootah
FROM alpine:3.23.3@sha256:25109184c71bdad752c8312a8623239686a9a2071e8825f20acb8f2198c3f659
RUN apk add --no-cache ca-certificates su-exec && adduser -D -u 10001 dootah && mkdir /state && chown 10001:10001 /state && chmod 0700 /state
COPY --from=build --chown=0:0 --chmod=0755 /dootah-server /dootah-server
COPY notices/ /licenses/
COPY --from=build /usr/local/go/LICENSE /licenses/GO-LICENSE
COPY LICENSE.md /licenses/XPREM-LICENSE.md
COPY --chown=0:0 --chmod=0755 entrypoint.sh /entrypoint.sh
RUN find /licenses -type d -exec chmod 0755 {} + \
 && find /licenses -type f -exec chmod 0644 {} + \
 && chown -R 0:0 /licenses \
 && mkdir -p /run/input && chown 0:0 /run/input && chmod 0700 /run/input
ENV DOOTAH_MODE=production DOOTAH_REQUIRE_PRODUCTION=true
ENTRYPOINT ["/entrypoint.sh", "/dootah-server"]
HEALTHCHECK --interval=20s --timeout=5s --start-period=30s CMD ["/dootah-server", "-health"]
