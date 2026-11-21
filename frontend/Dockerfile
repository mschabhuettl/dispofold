# Build context is the repository root (the image also ships LICENSE and THIRD_PARTY.md).
FROM node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS build
# The layout of the repository: scripts/build.mjs reads ../docs (the guide) and ../plugins (the shipped plugins,
# whose front ends import ../../../frontend/src/lib/plugin-api.js).
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
COPY docs/anleitung/ /src/docs/anleitung/
COPY docs/screenshots/ /src/docs/screenshots/
COPY plugins/sp5/plugin.json /src/plugins/sp5/plugin.json
COPY plugins/sp5/frontend/ /src/plugins/sp5/frontend/
COPY plugins/sp5/docs/ /src/plugins/sp5/docs/
RUN npm run build

# Serves the static interface and forwards API, login and health requests to the backend.
# Unprivileged nginx (user 101); the content belongs to root and is read only for it.
FROM nginxinc/nginx-unprivileged:1.30-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/rate-limit.conf /etc/nginx/conf.d/00-rate-limit.conf
COPY --from=build /src/frontend/dist /usr/share/nginx/html
COPY LICENSE THIRD_PARTY.md /usr/share/nginx/html/
EXPOSE 8080
