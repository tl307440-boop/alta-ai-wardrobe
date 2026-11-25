# Stage 1: Build Vue 3 + Vite + TypeScript static bundle (runs locally or in GitHub Actions, never on 2GB ECS)
FROM node:22-alpine AS builder
WORKDIR /build

RUN corepack enable && corepack prepare pnpm@9.15.0 --activate

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN NODE_OPTIONS="--max-old-space-size=2048" pnpm build

# Stage 2: Lightweight Nginx 1.27 Alpine (~10MB RSS)
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
