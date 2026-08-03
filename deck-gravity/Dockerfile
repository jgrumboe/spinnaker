# Gravity-themed Spinnaker Deck
#
# Multi-stage build:
#   1. Build Deck from local source
#   2. Set up Apache + inject Gravity theme
#
# Build (from repo root):
#   docker build -f deck-gravity/Dockerfile -t deck-gravity .
#
# Run:
#   docker run -p 9000:9000 deck-gravity

# =============================================================================
# Stage 1: Build Deck
# =============================================================================
FROM node:24-slim AS build

# Install build tools needed by native modules (bufferutil, utf-8-validate)
RUN apt-get update && \
    apt-get install -y python3 build-essential && \
    rm -rf /var/lib/apt/lists/*

RUN corepack enable && corepack prepare pnpm@11.1.1 --activate

WORKDIR /build/deck
COPY deck/ ./

# deck-kayenta is referenced by webpack alias as ../deck-kayenta relative to deck
COPY deck-kayenta/ /build/deck-kayenta/

# Install dependencies and build workspace packages, then the app
RUN pnpm install --frozen-lockfile && \
    pnpm modules && \
    pnpm build

# =============================================================================
# Stage 2: Runtime with Apache + Gravity theme
# =============================================================================
FROM debian:stable-slim
LABEL maintainer="deck-gravity"

WORKDIR /opt/deck

# Copy Apache setup from Deck's docker directory
COPY deck/docker /opt/deck/docker
RUN docker/setup-apache2.sh && \
    a2enmod substitute filter

# Copy built Deck assets
COPY --from=build /build/deck/build/webpack /opt/deck/html

# Copy Gravity theme assets (ARG busts cache when CSS changes)
ARG CACHEBUST=1
COPY deck-gravity/gravity-assets/gravity-theme.css /opt/deck/html/gravity-theme.css

# Inject Gravity theme <link> at end of body to load after webpack inline styles
RUN sed -i 's|</body>|<link rel="stylesheet" href="/gravity-theme.css" />\n</body>|' /opt/deck/html/index.html

RUN chown -R www-data:www-data /opt/deck
USER www-data

CMD ["sh", "docker/run-apache2.sh"]
