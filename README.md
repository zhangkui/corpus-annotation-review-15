# 多语言语料标注冲突审查系统

管理多语言语料片段、标注任务、标签层级、标注版本与仲裁结论，并对标注冲突进行合并展示与仲裁审查。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Scala 3.5 + Play 3（REST API） |
| 数据层 | TimescaleDB 2.15（PostgreSQL 16），事件日志使用 hypertable |
| 缓存/消息 | RabbitMQ 3.13（标注/仲裁事件总线） |
| 前端 | Blazor WebAssembly + .NET 8 |
| 部署 | Docker Compose 一键部署 |

## 快速开始

```bash
docker compose up --build
```

- 前端：http://localhost:8080 （nginx 托管 Blazor WASM，并将 `/api` 反向代理到后端）
- 后端 API：http://localhost:9000/api/health
- RabbitMQ 管理台：http://localhost:15672 （guest/guest）
- TimescaleDB：localhost:5432 （corpus/corpus，库名 corpus）

首次启动时 `db/init.sql` 自动建表并写入演示数据（用户、标签树、多语言片段、批次与任务）。

启动后可运行冒烟测试，逐条验证五个核心业务规则：

```bash
./scripts/smoke.sh                    # 打向 http://localhost:9000
./scripts/smoke.sh http://localhost:8080   # 也可经前端 nginx 反代
```


## 目录结构

```
├── docker-compose.yml        # 一键部署编排
├── db/init.sql               # TimescaleDB 模式 + 种子数据
├── backend/                  # Scala 3.5 + Play 3 后端
│   ├── build.sbt
│   ├── conf/{application.conf,routes,logback.xml}
│   └── app/{models,repositories,services,controllers,messaging}
└── frontend/                 # Blazor WebAssembly (.NET 8)
    ├── CorpusReviewClient/   # Pages / Services / Models
    └── nginx.conf            # 静态托管 + /api 反代
```

## 核心业务规则及其实现位置

1. **重复标注合并展示** — 同一片段、同一标签、同一字符范围的重复标注在查询时按
   `(segment_id, tag_id, start_offset, end_offset)` 分组合并，返回次数与来源（标注者/版本）。
   见 `AnnotationRepository.mergedBySegment` 与 `GET /api/segments/:id/annotations/merged`。
2. **标签层级不能形成环** — 创建/改挂标签时用递归 CTE 沿父链检查，若新父节点的祖先中包含
   自身则拒绝（409）。见 `TagService` / `TagRepository.ancestorContains`。
3. **仲裁只能引用已有标注版本并生成新结论** — 创建仲裁时校验所有 `sourceVersionIds`
   必须存在于 `annotation_version` 且其任务覆盖该片段；仲裁结论写入独立的
   `arbitration_annotation`，不改动原标注。见 `ArbitrationService`。
4. **任务分配避免同一用户连续获得同一批次** — 分配前查询该用户最近一次分配的批次，
   若与当前任务批次相同则拒绝（409）；自动分配只挑选最近批次不同的标注员。
   见 `AssignmentService`。
5. **导入校验字符偏移与语言代码** — 导入时校验语言代码（BCP-47 形态 + 主子标签白名单），
   并按 **Unicode 码点** 校验 `0 <= start < end <= 文本码点长度`。见 `ImportService`。

## API 一览

```
GET  /api/health
GET  /api/users                          POST /api/users
GET  /api/corpora                        POST /api/corpora
GET  /api/segments                       POST /api/segments
GET  /api/segments/:id
GET  /api/segments/:id/annotations/merged   # 规则1：合并展示
GET  /api/segments/:id/versions
GET  /api/segments/:id/arbitrations
GET  /api/tags  /api/tags/tree           POST /api/tags
PUT  /api/tags/:id/parent                   # 规则2：环检测
GET  /api/batches                        POST /api/batches
GET  /api/tasks                          POST /api/tasks
GET  /api/tasks/:id  /api/tasks/:id/assignments
POST /api/tasks/:id/assign                  # 规则4：连续批次校验
POST /api/tasks/:id/auto-assign
POST /api/versions                          # 提交标注版本
GET  /api/versions/:id
POST /api/arbitrations                      # 规则3：引用已有版本
GET  /api/arbitrations/:id
POST /api/import/segments                   # 规则5：导入校验
GET  /api/events                            # TimescaleDB hypertable 事件流
```

## 事件流（RabbitMQ）

标注提交、任务分配、仲裁完成等事件发布到 topic 交换机 `corpus.events`；
`event-log` 队列的消费者把事件写入 TimescaleDB hypertable `annotation_event`。
RabbitMQ 不可用时事件直接落库，保证功能可用。

## 本地开发

```bash
# 后端（需要 JDK 17+ 与 sbt 1.10+）
cd backend && sbt run

# 前端（需要 .NET 8 SDK）
cd frontend/CorpusReviewClient && dotnet run
# 开发时把 wwwroot/appsettings.json 的 ApiBaseUrl 指向 http://localhost:9000/

# 依赖中间件
docker compose up db rabbitmq
```
