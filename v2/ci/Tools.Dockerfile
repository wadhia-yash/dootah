FROM golang@sha256:8ac98ca534ac3f51e1f420a1dd2c15e74c75cfa0f23f3ad27eb5d7236c349a0c AS build
ENV CGO_ENABLED=0
RUN go install github.com/rhysd/actionlint/cmd/actionlint@03d0035246f3e81f36aed592ffb4bebf33a03106 && \
    go install github.com/zricethezav/gitleaks/v8@9c72c5f9f05200fdc06e3f1b16e9aaa89fbe9f75
FROM alpine:3.23.3@sha256:25109184c71bdad752c8312a8623239686a9a2071e8825f20acb8f2198c3f659
RUN apk add --no-cache git ca-certificates
COPY --from=build /go/bin/actionlint /go/bin/gitleaks /usr/local/bin/
WORKDIR /src
