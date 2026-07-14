# syntax=docker/dockerfile:1

# =============================================================================
# DSA Progress Tracker — frontend image (task 12.2)
# Multi-stage build: (1) build the static bundle with Node, (2) serve it with
# nginx. The nginx image is the primary deliverable.
#
# Alternative: this static bundle can also be deployed straight to Vercel or
# Netlify (per task 12.2) — point their build command at `npm run build` and
# publish `dist/`. The nginx image below is the self-hosted equivalent.
#
# API/WS endpoints are baked in at BUILD time because Vite inlines VITE_* env
# vars into the bundle. Override them per environment with build args:
#   docker build \
#     --build-arg VITE_API_BASE_URL=https://api.example.com/api \
#     --build-arg VITE_WS_URL=https://api.example.com/ws \
#     -t dsa-tracker-frontend .
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 — build
# -----------------------------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /app

# Install dependencies first (cached until package files change). `npm ci`
# installs exactly what package-lock.json pins — reproducible and fast.
COPY package.json package-lock.json ./
RUN npm ci

# Build-time API/WS endpoints. Defaults match local dev; override via build args.
ARG VITE_API_BASE_URL=http://localhost:8080/api
ARG VITE_WS_URL=http://localhost:8080/ws
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_WS_URL=$VITE_WS_URL

# Copy source and produce the static bundle in dist/.
COPY . .
RUN npm run build

# -----------------------------------------------------------------------------
# Stage 2 — serve
# -----------------------------------------------------------------------------
FROM nginx:alpine AS runtime

# SPA-aware config (client-side routing fallback + optional API/WS proxy).
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Static assets produced by the build stage.
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# nginx:alpine's default entrypoint runs nginx in the foreground.
CMD ["nginx", "-g", "daemon off;"]
