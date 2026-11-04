FROM node:24-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
LABEL org.opencontainers.image.title="Let's Talk frontend"
ENV BACKEND_SERVERS="server backend:8080;"
ENV NGINX_ENVSUBST_FILTER=BACKEND_SERVERS
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
