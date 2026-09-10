# syntax=docker/dockerfile:1.7

FROM node:24-bookworm-slim AS dependencies

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

FROM node:24-bookworm-slim AS build

WORKDIR /workspace/frontend
COPY --from=dependencies /workspace/frontend/node_modules ./node_modules
COPY frontend/ ./
RUN npm run build

FROM node:24-bookworm-slim AS runtime

RUN groupadd --system --gid 1001 nodejs \
    && useradd --system --uid 1001 --gid nodejs --home-dir /app nextjs

WORKDIR /app
COPY --from=build --chown=nextjs:nodejs /workspace/frontend/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /workspace/frontend/.next/static ./.next/static

ENV NODE_ENV=production \
    HOSTNAME=0.0.0.0 \
    PORT=3000
EXPOSE 3000
USER nextjs

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
  CMD node -e "fetch('http://127.0.0.1:3000/api/health').then(response => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["node", "server.js"]
