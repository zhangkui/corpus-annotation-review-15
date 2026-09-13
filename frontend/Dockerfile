# ---------------------------------------------------------------------------
# 构建阶段：.NET 8 SDK 发布 Blazor WebAssembly
# ---------------------------------------------------------------------------
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src

COPY CorpusReviewClient/CorpusReviewClient.csproj CorpusReviewClient/
RUN dotnet restore CorpusReviewClient/CorpusReviewClient.csproj

COPY . .
RUN dotnet publish CorpusReviewClient/CorpusReviewClient.csproj -c Release -o /app/publish

# ---------------------------------------------------------------------------
# 运行阶段：nginx 托管静态文件并反代 /api
# ---------------------------------------------------------------------------
FROM nginx:1.27-alpine
COPY --from=build /app/publish/wwwroot /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
