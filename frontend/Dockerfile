FROM node:24-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx/nginx-unprivileged:stable-alpine-slim
LABEL org.opencontainers.image.title="Let's Talk frontend"
# Apply available Alpine security fixes while keeping the runtime unprivileged.
USER root
RUN apk upgrade --no-cache
USER 101
ENV BACKEND_SERVERS="server backend:8080;"
ENV PUBLIC_SCHEME=http
ENV UPLOAD_MAX_BODY_SIZE=201m
ENV NGINX_ENVSUBST_FILTER="^(BACKEND_SERVERS|PUBLIC_SCHEME|UPLOAD_MAX_BODY_SIZE)$"
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
