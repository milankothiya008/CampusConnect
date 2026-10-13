# syntax=docker/dockerfile:1.7
# The edge: Nginx serves the built React app and proxies /api, /uploads and /ws (WebSocket) to the API
# replicas. Build context: the repository root (docker compose sets it).

FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
ARG VITE_UNIVERSITY_DOMAIN=ddu.ac.in
ARG VITE_UNIVERSITY_TIMEZONE=Asia/Kolkata
ARG VITE_UNIVERSITY_TZ_OFFSET=+05:30
ENV VITE_API_URL=/api VITE_WS_URL= \
    VITE_UNIVERSITY_DOMAIN=$VITE_UNIVERSITY_DOMAIN VITE_UNIVERSITY_TIMEZONE=$VITE_UNIVERSITY_TIMEZONE VITE_UNIVERSITY_TZ_OFFSET=$VITE_UNIVERSITY_TZ_OFFSET
RUN npm run build \
    && node -e "const fs=require('fs'),c=require('crypto');const html=fs.readFileSync('dist/index.html','utf8');const hashes=[...html.matchAll(/<script>([\\s\\S]*?)<\\/script>/g)].map(m=>\"'sha256-\"+c.createHash('sha256').update(m[1]).digest('base64')+\"'\");fs.writeFileSync('dist/.csp-hashes',hashes.join(' '));"

FROM nginx:1.29-alpine
COPY deploy/web/nginx.conf /etc/nginx/nginx.conf
COPY deploy/web/snippets/ /etc/nginx/snippets/
COPY deploy/web/sites/ /etc/nginx/sites/
COPY deploy/web/entrypoint/ /docker-entrypoint.d/
RUN chmod +x /docker-entrypoint.d/*.sh && rm -f /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
