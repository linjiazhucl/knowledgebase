# 澄明知识库 · Enterprise AI Knowledge Base

基于 PRD v1.1 的企业 AI 知识库管理端，前端通过 Spring Boot API 读取和写入 MySQL 业务数据、Redis 登录与问答历史，Milvus 负责向量写入和相似度检索。

## 本地运行

需要 Node.js 18+。

```bash
npm install
npm run dev
```

浏览器访问 `http://localhost:5174`，管理端入口为 `http://localhost:5174/admin`。

演示账号：`admin` / `admin123`

## Docker 运行

```bash
docker compose up -d --build
```

启动后访问 `http://localhost:5174`。停止服务：

```bash
docker compose down
```

## 端口约定

- Vite 开发/预览：`5174`
- 后端 API 预留：`18080`
- Milvus standalone 预留：`19530`（健康检查预留 `19091`）

以上端口避开现有项目已使用的 `81`、`8080`、`16379`、`13306`、`19000`、`19001`。

## 说明

- 管理端登录态存储在 Redis，默认账号为 `admin` / `admin123`。
- 仪表盘、知识库、文档、片段、用户、模型和系统配置均来自后端接口，不依赖前端演示数组。
- 问答使用 SSE；命中来源由 Milvus 返回片段 ID，再从 MySQL 回查文档和片段内容，问答记录写入 Redis。
- 默认 Docker 端口为前端 `5174`、后端 `18080`、Milvus gRPC `19530`、Milvus REST `19091`，避开现有项目的 `81`、`8080`、`16379`、`13306`、`19000`、`19001`。
