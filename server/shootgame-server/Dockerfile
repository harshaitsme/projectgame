# syntax=docker/dockerfile:1

# ---------- build stage ----------
FROM rust:1-slim-bookworm AS builder
WORKDIR /app

# Copy the whole crate (proto/, build.rs, src/, Cargo.*). `.dockerignore`
# excludes target/, so this stays small. build.rs uses protoc-bin-vendored,
# so no system protoc is needed.
COPY . .

# Build the optimized release binary.
RUN cargo build --release --bin shootgame-server \
    && strip target/release/shootgame-server

# ---------- runtime stage ----------
FROM debian:bookworm-slim

# Minimal runtime deps.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
RUN useradd --system --home /home/shootgame --create-home --shell /usr/sbin/nologin shootgame
WORKDIR /home/shootgame

COPY --from=builder /app/target/release/shootgame-server /usr/local/bin/shootgame-server

USER shootgame

# TCP = control/login & packets; UDP = low-latency state broadcasts.
EXPOSE 54555/tcp 54777/udp

ENV SHOOTGAME_TCP_PORT=54555
ENV SHOOTGAME_UDP_PORT=54777

ENTRYPOINT ["shootgame-server"]
