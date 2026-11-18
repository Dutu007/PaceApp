FROM golang:1.23-alpine AS build
WORKDIR /src
COPY . .
RUN go mod tidy
RUN go test ./...
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/paceapp-api .

FROM alpine:3.21
RUN adduser -D -H -u 10001 app
COPY --from=build /out/paceapp-api /paceapp-api
USER app
EXPOSE 8080
ENTRYPOINT ["/paceapp-api"]
