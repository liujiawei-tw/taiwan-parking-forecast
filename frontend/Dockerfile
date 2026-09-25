FROM node:24-alpine AS build
WORKDIR /workspace
ARG VITE_API_BASE_URL=http://localhost:8080
ARG VITE_GOOGLE_MAPS_BROWSER_API_KEY=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_GOOGLE_MAPS_BROWSER_API_KEY=$VITE_GOOGLE_MAPS_BROWSER_API_KEY
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80