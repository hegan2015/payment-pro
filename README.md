# payment-pro 详细设计文档

> 参考支付宝领域划分的工业级支付系统
> Spring Boot 3.2 + Apache Dubbo 3.2 + 自研 XTS 风格 TCC + 事务性 Outbox
> 版本：2.0.0（P0 23/23 + P1 47/47 + P2 17/19 验收；I-1 批准不做列入部署必改，I-6 待实现）｜ 文档日期：2026-11-20
> **文档三件套**：[REQUIREMENTS.md](REQUIREMENTS.md)（需求规格与验收）· [ARCHITECTURE.md](ARCHITECTURE.md)（架构与不变量红线）· 本文（详细设计/字段级 DDL/代码走读）
> v2.0 P2 更新：**增值生态 17/19 验收**（详见 §21）——交易产品/担保/预授权/超时关闭、合并支付与分阶段、撤销vs退款边界与改价、组合支付叠加、保证金垫资、多币种、营销抵扣编排、开放平台应用授权/沙箱/配额计费/版本SDK、签约代扣、账户生命周期、多客户端、分片与影子双跑、灰度、可观测。**管理台 22 个原待建页全部建成真实页面并浏览器逐页实测 22/22 通过**，12 业务分组两级导航 69 项。
> **v2.0 收口挖出并修复 8 个真缺陷**（T-5/T-6 代码早已落盘但验证脚本从未跑过，一跑即暴露；详见 §21.10）：MERGE_COMBO 退款腿级不守恒、分阶段撤销后无法重付、改价原价留痕被覆盖、合并受理前失败悬挂 PAYING、TCC 回滚不重开合并单、reopenMerge 拒绝 CLOSED 终态子订单、清算跑批 8s 超时误判 503、监控逐条远程查对端自撞 429。
> **v2.0 双账本修复**（ride-pro 侧，详见 ARCHITECTURE §9.3）：在途误确认终态守卫、CARD 出资改外部清算户、监控基线污染、监控自撞限流；历史冲正已执行（pp RV8504 + ride 4 笔冲正腿），17 映射户全平、重算后 48+ 周期零报警。
> v1.9 P1 更新：**核心支付能力 47 卡全验收**（详见 §20）——渠道 SPI/沙箱/路由择优/对账文件/限额维护窗、QLExpress 风控规则引擎+多维限额+名单+评分分级、KYC/进件审核流/C端登录/多账户类型/绑卡/四原因冻结、T+N 结算+三层费率+分账守恒+提现沙箱+商户账单、会计科目体系+日切日终+账簿凭证+账实核对、多事件通知+查询重发+Swagger+统一错误码+双实例无状态与灰度、担保/预授权/多产品路由/超时关闭、日切批量与渠道内外对账+报表+分片调度、支付路由/组合支付按腿落账/分账编排/退款异步化/关闭撤销、收银台多方式渲染+付款码扫码+支付验证。**v2.0 更正：平台不自建 C 端 H5**，收银台能力以 `cashier.*` OpenAPI 开放给业务方自渲染（ride-pro 乘客端已落地，视觉对标支付宝）。十专项全绿（channel 111/risk 116/member-account 109/settle 158/accounting 255/notify-gw 149/trade 131/recon 83/payment-orchestration 107/cashier 199），smoke 79 + verify-pro 22 + **ride 24/24 门禁六次通过**。新增七条流程护栏见 §20.11。
> v1.9 更新：P0 生产化升级（详见 §19）——账务红冲蓝补/挂账科目与凭证防篡改、差错处理平台与自动冲正策略（默认影子模式）、渠道异步回执 + tcc-core WAITING 增量与补单收敛、通知 HMAC/RSA2 签名、控制台 OAuth2 + RBAC + 审计、商户密钥多版本轮换与证书到期告警、大额双人复核 + 备付金账实核对 + 二次认证、13 域 Micrometer/Prometheus 可观测。已验收 17/23 卡：smoke 79、verify-pro 22、reverse 16、suspense 28、notify-sign 20、dispute 73、fund-guard 34+8、rbac 51、secret 32、metrics 96、reconverge 26，**ride-pro verify-integration 24/24 兼容门禁通过**。wave3（Apollo/XXL-Job/Sentinel/事务消息）与 I-5 分片进行中。
> v1.8 更新：Docker 中间件集成（详见 §18）——双 profile（local 零依赖 / docker 共享栈）；MySQL 单实例多库 pp_*（序列函数同构）；RocketMQ PP_OUTBOX 主投递 + outbox 轮询兜底；Redis Lua 风控滑动窗口 + 网关商户缓存；Docker ZooKeeper 2181（跳过内嵌 registry）；run.sh --docker + 只读 H2→MySQL 迁移工具。smoke 79/79、verify-pro 22/22（docker）回归通过。文档已全面对齐双 profile 实现：§1.2 关键取舍（outbox 为可靠性底座/MQ 可插拔、H2 local·MySQL docker）、§1.3 技术选型（全栈版本与取舍）、§5.1 存储选型、§12.1 验签（MerchantCache）、附录 A 代码索引、附录 C DDL（MySQL 方言/POST/订单幂等键）、附录 D 契约（post/entriesPage/fundPay）、附录 E 走读（5 参 insert+afterCommit MQ 主投递、行号刷新）、附录 F 边界（F.9 中间件降级与资金单）
> v1.7 更新：充值/提现升级为资金单（fund.topup/fund.withdraw → 交易单+支付单，biz_type 区分 TRADE/TOPUP/WITHDRAW，SYS_FUND 系统资金商户、fee=0、BALANCE、完整 TCC）；账务流水跨账户全局分页查询（详见 §17）
> v1.3 更新：HTTPS 8443 双连接器 + 自签证书自动生成；OpenAPI 扩展账户/退款/查询端点与通用幂等过账，支撑 ride-pro 双账本镜像接入（详见 §16）
> v1.2 更新：TCC 抽成零业务耦合通用组件 tcc-core（com.qoder.tcc，自动装配）；随后整体移除 payment-dtx 模块，业务模块直依赖 tcc-core（16 模块），错误码映射下沉 FacadeSupport，详见 §3.2 与 §6.7
> v1.1 细化：新增附录 C 字段级数据字典、附录 D 接口契约清单、附录 E 核心代码走读、附录 F 异常与边界场景全集

---

## 目录

1. [设计目标与总体思路](#1-设计目标与总体思路)
2. [总体架构](#2-总体架构)
3. [Maven 工程结构](#3-maven-工程结构)
4. [服务通信与治理](#4-服务通信与治理)
5. [数据架构](#5-数据架构)
6. [分布式事务设计（XTS 风格 TCC）](#6-分布式事务设计xts-风格-tcc)
7. [幂等体系设计](#7-幂等体系设计)
8. [异步消息与可靠投递](#8-异步消息与可靠投递)
9. [各领域详细设计](#9-各领域详细设计)
10. [核心流程时序](#10-核心流程时序)
11. [账务模型](#11-账务模型)
12. [安全设计](#12-安全设计)
13. [可观测性与运维](#13-可观测性与运维)
14. [测试与验证体系](#14-测试与验证体系)
15. [已知边界与演进方向](#15-已知边界与演进方向)
16. [OpenAPI 商户接入扩展（ride-pro 双账本对接）](#16-openapi-商户接入扩展v130ride-pro-双账本对接)
17. [资金单与全局分页流水（v1.7.0）](#17-资金单与全局分页流水v170)
18. [Docker 中间件集成（MySQL/RocketMQ/Redis/ZooKeeper）](#18-docker-中间件集成mysqlrocketmqrediszookeeper)
19. [P0 生产化升级（v1.9.0）](#19-p0-生产化升级v190)
20. [P1 核心支付能力（v1.9.0，47 卡全验收）](#20-p1-核心支付能力v19047-卡全验收)

**深度附录（v1.1 细化）**

- [附录 C：数据字典（字段级 DDL）](#附录-c数据字典字段级-ddl)
- [附录 D：接口契约清单](#附录-d接口契约清单)
- [附录 E：核心代码走读](#附录-e核心代码走读)
- [附录 F：异常与边界场景全集](#附录-f异常与边界场景全集)

---

## 1. 设计目标与总体思路

### 1.1 目标

在"零外部依赖、一键可运行"的约束下，实现一套具备工业级正确性保障的支付系统：

| 目标 | 落地手段 |
|---|---|
| 领域划分对齐支付宝 | 会员 / 账户 / 账务 / 交易 / 支付核心 / 收银台 / 渠道 / 风控 / 清结算 / 消息 / 对账，11 个业务域独立进程 |
| 工程化 | Maven 多模块 reactor + Maven Wrapper，依赖版本集中管理（BOM） |
| 远程调用 | Apache Dubbo 3.2 + ZooKeeper 注册中心，接口契约独立模块 |
| 分布式事务 | 自研 XTS 风格 TCC：一阶段同步预留、二阶段异步驱动、指数退避补偿 |
| 工业级幂等 | 唯一键先插占位 + 同事务结果回填 + 回放先于状态检查 + CAS 状态机 |
| 异步补偿 | 事务性 outbox + 双调度恢复（TCC recover / outbox dispatch）+ 通知退避重试 |
| 资金正确性 | 复式记账、冻结/解冻、试算平衡、五方对账 |
| 开箱即用 | local profile 零依赖：H2 嵌入式文件库 + 内嵌 ZooKeeper，`./run.sh` 一键编译启动 13 进程；docker profile 接共享中间件（MySQL/RocketMQ/Redis/ZK），`./run.sh --docker`（见 §18） |

### 1.2 关键取舍（为什么这样设计）

**为什么自研 TCC 而不是 Seata？**
- local profile 的硬约束是零外部依赖：Seata 需要独立部署 TC Server，违背"一键运行"。自研协调器内嵌于 payment-payment 进程，无需额外集群。
- 即便 docker profile 放开了中间件依赖，仍保留自研 tcc-core（不引入 Seata）：嵌入式协调器 + 围栏表的正确性机制与 Seata TCC 同构，迁移收益不抵改造面，故 MQ/MySQL/Redis 可插拔而 TCC 内核不动。
- 支付场景天然适合 TCC：资金预留（冻结）、渠道预授权、订单预留都是标准的"资源预留"语义，比 AT 模式的全局锁更贴合支付业务，且无脏写问题。
- 自研可以完整展示 XTS 的核心机制（事务日志、分支日志、空回滚、防悬挂、补偿恢复），这些机制与蚂蚁 XTS / Seata TCC 的设计同构，代码即文档。

**为什么以事务性 outbox 为可靠性底座，MQ 只作可插拔主投递？**
- outbox 把"业务终态 + 待发消息"放进同一个本地事务，原子性由数据库保证——**无论是否接 MQ，这一步都不变**。这是分布式消息最终一致性的标准解法，正确性与 MQ 事务消息等价（RocketMQ 事务消息也需半消息 + 回查来兜底原子性，本质同源）。
- local profile（零依赖默认）：仅靠 outbox + 3s 轮询调度补偿投递，不引入任何外部 MQ。
- docker profile：RocketMQ `PP_OUTBOX` 作为主投递通道（事务提交后 afterCommit 发送，低延迟），outbox 行仍同事务写入并留宽限期，轮询作为兜底；MQ 发送失败被吞掉留 PENDING 交轮询、消费失败 RECONSUME_LATER 重投，退避重试耗尽置 DEAD 等对账/人工（见 §18.3）。
- 语义：**至少一次投递 + 接收端幂等 = 恰好一次效果**。MQ 只是把"投递"从轮询加速为推送，可靠性语义完全由 outbox + 幂等保证，故 MQ 可插拔、不改变正确性论证。

**为什么 H2 文件库（local）/ MySQL 多库（docker）+ 每域一库？**
- 正确性依赖真实数据库能力：事务、行锁（SELECT FOR UPDATE）、唯一约束、序列——TCC 与幂等的所有论证都建立在此之上，内存 Map 做不到。H2 文件模式与 MySQL 都提供这些能力，故论证在两个 profile 下通用。
- local profile 用 H2 嵌入式文件库（`data/<domain>.mv.db`），零外部依赖、一键运行；docker profile 切共享 MySQL 单实例多库（`pp_<domain>`），9 域各自独立 database（见 §18.2）。
- "每域一库"在两个 profile 下一致：跨域绝不共享表，只通过 Dubbo 接口访问，落实微服务数据自治。当初为平滑迁移 MySQL 分库预留的边界，已在 docker profile 兑现（schema-mysql.sql 一次性建表 + 序列方言适配，业务 SQL 零改动）。

**为什么一阶段同步、二阶段异步？**
- Try 同步执行：失败立即整体回滚并给用户明确错误（余额不足、渠道拒绝等），用户体验与资金安全都要求 Try 阶段强一致反馈。
- Confirm/Cancel 异步驱动：二阶段只操作"已预留"的资源，理论上必然成功，失败只是暂时性的（网络/宕机），交给补偿调度按退避重试，接口快速返回 PROCESSING，客户端轮询终态。这是 XTS 的标准形态。

### 1.3 技术选型

主流正式框架 + 自研事务内核，版本由 Spring Boot parent 与 dubbo-bom 统一托管（仅 MyBatis/ZooKeeper/RocketMQ 显式钉版）。选型以"资金正确性可论证 + local 零依赖一键运行 + docker 可平滑切生产共享栈"为准绳。

| 层次 | 技术 | 版本 | 选型理由 | 备选与取舍 |
|---|---|---|---|---|
| 语言/运行时 | Java | 17（release 17） | LTS，Spring Boot 3.x 基线要求 17+；switch 表达式/record 提升表达力 | 21（未采用，3.2.5 基线 17 已足够） |
| 应用框架 | Spring Boot | 3.2.5 | 自动装配 + 内嵌 Tomcat + 生产级 starter；`@ConditionalOnProperty` 实现双 profile 开关，local 不加载中间件 Bean | Spring Cloud（过重，RPC 治理交给 Dubbo） |
| RPC/服务治理 | Apache Dubbo | 3.2.14 | 高性能 RPC + 接口级 facade 契约 + `group` 路由 TCC 参与者 + 注册发现 | OpenFeign/HTTP（契约与性能弱）、gRPC（需另建治理生态） |
| 注册中心 | ZooKeeper | 3.8.4 | Dubbo 经典 CP 注册中心；local 内嵌 `ZooKeeperServer` 零依赖，docker 共享 ZK 2181 | Nacos（需额外部署，违背 local 零依赖） |
| 注册客户端 | Curator 5 | dubbo-dependencies-zookeeper-curator5 | Dubbo 官方 ZK 适配，排除 log4j/reload4j 统一日志 | — |
| ORM | MyBatis Spring Boot Starter | 3.0.3 | 注解式 Mapper，SQL 完全可控（`SELECT FOR UPDATE`/唯一键/CAS 行数检查），贴合资金正确性论证 | JPA/Hibernate（隐式 SQL/脏检查不利于精确资金控制） |
| 数据库（local） | H2 | 2.2.224（Boot 托管） | 嵌入式文件库，真实事务/行锁/唯一约束/序列，`MODE=MySQL` 方言贴近生产，零外部依赖 | SQLite（锁/序列弱）、内存 Map（无事务，正确性不成立） |
| 数据库（docker） | MySQL + mysql-connector-j | 8.3.0（Boot 托管） | 生产级关系库，单实例多库 `pp_*`；序列用 `sequences` 表+存储函数与 H2 `ALIAS` 同构，业务 SQL 零改动 | PostgreSQL（MySQL 更贴合支付宝口径与团队栈） |
| 分布式事务 | 自研 tcc-core（com.qoder.tcc） | 工程内组件 | XTS 风格 TCC，嵌入式协调器+围栏表，零业务耦合；资金预留语义贴合、无脏写（见 §1.2/§6） | Seata（需独立 TC 集群，违背 local 零依赖；docker 亦保留自研） |
| 可靠消息 | 事务性 Outbox + RocketMQ client | 5.1.4 | outbox 同事务原子落库为底座；docker `PP_OUTBOX` 主投递加速 + 轮询兜底；原生 client（非 starter）精确控制 afterCommit/消费幂等 | Kafka（运维重）、纯 MQ 事务消息（仍需回查兜底，见 §1.2/§8） |
| 缓存/限流 | Spring Data Redis（Lettuce） | Boot 托管 | docker 风控 Lua 滑动窗口（跨实例共享）+ 网关商户凭证缓存 TTL60s；Lettuce 异步线程安全 | Jedis（同步阻塞）、Caffeine（本地缓存无法跨实例共享限流窗口） |
| JSON 序列化 | Jackson | Boot 托管 | `TccContextUtil` 统一 toJson/fromJson（outbox payload/TCC context/缓存值） | Gson/Fastjson（Boot 原生 Jackson，免额外依赖与安全历史包袱） |
| 日志 | SLF4J + Logback | Boot 托管 | 全链路 traceId（MDC）+ 业务常规日志；排除 log4j/reload4j 统一 Logback | Log4j2（Boot 默认 Logback 足够） |
| Web/接入 | spring-boot-starter-web | Boot 3.2.5 | 网关 REST + 静态控制台 + HTTPS 8443 双连接器（自签证书） | WebFlux（本系统阻塞式 JDBC/Dubbo，Servlet 栈更贴合） |
| 代码简化 | Lombok | Boot 托管 | `@RequiredArgsConstructor`/`@Slf4j`/`@Data` 消除样板，构造器注入 | — |
| 构建 | Maven（mvnw wrapper） | 3.x | 16 模块多模块工程，Boot parent + dubbo-bom 统一版本；wrapper 免环境差异 | Gradle（团队栈 Maven） |

**双 profile 选型哲学**：同一套业务代码与 facade 契约，靠 `@ConditionalOnProperty` 切换基础设施——local 零依赖（H2 + 内嵌 ZK + outbox 轮询 + 内存限流）保证"开箱即用、正确性可论证"；docker 接生产共享栈（MySQL + RocketMQ + Redis + 共享 ZK）保证"低延迟、跨实例共享、可水平扩展"。中间件可插拔而 TCC 内核与资金语义不动（详见 §18）。

**版本治理**：H2/MySQL/Lettuce/Jackson/Lombok/Logback/Netty 一律由 `spring-boot-starter-parent` 3.2.5 托管，避免版本漂移；Dubbo 生态由 `dubbo-bom` 3.2.14 对齐；显式钉版仅 `mybatis-starter` 3.0.3、`zookeeper` 3.8.4、`rocketmq-client` 5.1.4（Boot 未托管或需独立于 Dubbo 传递版本）。

---

## 2. 总体架构

### 2.1 进程拓扑

```
                        ┌────────────────────────────┐
   浏览器 / OpenAPI ───▶ │  payment-gateway  :8080    │  HTTP + 静态控制台
                        │  (Dubbo :20880, 纯消费端)   │
                        └─────────────┬──────────────┘
                                      │ Dubbo RPC
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
┌───────▼───────┐            ┌────────▼────────┐           ┌────────▼────────┐
│ payment-cashier│───pay────▶│ payment-payment │◀──check───│  payment-risk   │
│ 收银台 :20886  │            │ 支付核心 :20885  │           │  风控 :20888    │
└───────────────┘            │ TCC 协调器宿主   │           └─────────────────┘
                             └───┬────┬────┬───┘
                    Try/Confirm/ │    │    │ outbox 投递
                    Cancel(Dubbo)│    │    ├──────────────▶ payment-settle :20889
              ┌──────────────────┘    │    └──────────────▶ payment-notify :20890
              │                       │                          │ HTTP POST 回调
   ┌──────────▼─────────┐   ┌─────────▼─────────┐                ▼
   │ payment-accounting │   │  payment-channel  │            商户系统
   │ 账务核心 :20883     │   │  渠道网关 :20887   │
   │ (复式记账/余额权威)  │   │  (模拟银行)       │
   └──────────┬─────────┘   └───────────────────┘
              │ 账户状态校验
   ┌──────────▼─────────┐   ┌───────────────────┐   ┌───────────────────┐
   │  payment-account   │   │  payment-trade    │   │  payment-member   │
   │  账户域 :20882      │   │  交易域 :20884     │   │  会员域 :20881     │
   └────────────────────┘   └───────────────────┘   └───────────────────┘

   ┌────────────────────┐   ┌───────────────────────────────────────────┐
   │  payment-recon     │   │  payment-registry（内嵌 ZooKeeper :2181）   │
   │  对账域 :20891      │   │  健康 HTTP :8092                           │
   │  五方核对(只读聚合)  │   │  所有服务的注册中心                          │
   └────────────────────┘   └───────────────────────────────────────────┘
```

> 上图为 **local 默认形态**（内嵌 ZK + 各域 H2 文件库）。网关另有 **HTTPS :8443** 双连接器（OpenAPI 商户接入，§16.1）。`docker` profile 下注册中心替换为共享 Docker ZooKeeper :2181（跳过内嵌 registry）、存储改 MySQL `pp_*` 多库、outbox 改 RocketMQ 主投递、风控/缓存改 Redis，详见 [§18](#18-docker-中间件集成mysqlrocketmqrediszookeeper)。

### 2.2 域职责一览

| 域 | 进程 | Dubbo 端口 | 职责 | 数据库 |
|---|---|---|---|---|
| 网关 | payment-gateway | 20880 | HTTP 入口、OpenAPI 验签、聚合查询、静态控制台 | 无 |
| 注册中心 | payment-registry | — | local 内嵌 ZooKeeper（2181）+ 健康探针（8092）；docker 共享 ZK，跳过内嵌 | 无 |
| 会员 | payment-member | 20881 | 用户/商户注册、appId/appSecret、冻结 | member |
| 账户 | payment-account | 20882 | 支付账户开立、状态管理（余额权威在账务域） | account |
| 账务 | payment-accounting | 20883 | 复式记账、余额/冻结、充值、试算平衡 | accounting |
| 交易 | payment-trade | 20884 | 订单生命周期状态机（CAS） | trade |
| 支付核心 | payment-payment | 20885 | 支付/退款编排、TCC 协调器、outbox | payment |
| 收银台 | payment-cashier | 20886 | 支付方式渲染、支付受理转发 | 无 |
| 渠道 | payment-channel | 20887 | 模拟银行：预授权/扣款/撤销/退款 | channel |
| 风控 | payment-risk | 20888 | 单笔限额、滑动窗口频率（local 内存 / docker Redis） | 无 |
| 清结算 | payment-settle | 20889 | 结算单登记、商户汇总 | settle |
| 消息 | payment-notify | 20890 | 商户通知登记、HTTP 推送、退避重试 | notify |
| 对账 | payment-recon | 20891 | 五方核对、报告落库 | recon |

> 数据库列名为 local H2 库名；docker profile 对应 MySQL 库 `pp_<domain>`（如 pp_payment），单实例多库，见 §18.2。

### 2.3 依赖方向原则

- **网关/收银台/对账是纯聚合层**：只消费 facade，不持有业务数据。
- **支付核心是唯一的事务编排者**：TCC 协调器只部署在 payment-payment，账务/渠道/交易只是参与者。编排逻辑集中，避免协调器分布式部署带来的一致性复杂度。
- **账务域是余额唯一权威**：账户域只管"账户实体与状态"，不存余额；任何余额变动必须走账务域复式记账。这对齐支付宝"账户与账务分离"的设计。
- **跨域数据零共享**：每个域只访问自己的库；对账需要跨域数据时走只读 facade 聚合。

---

## 3. Maven 工程结构

### 3.1 模块清单（16 个）

```
payment-pro (parent, pom)
├── tcc-core            通用 TCC 组件（零业务耦合）：协调器/参与者模板/幂等模板/补偿/自动装配
├── payment-common      基础库：Result/ResultCode/BizException/MoneyUtil/TraceFilter（含 TccException 边界转码）+ db/H2Sequence（H2 序列 alias）
├── payment-facade      契约库：全部 Dubbo 接口 + DTO + TCC 上下文 + HealthFacade(Impl)
├── payment-registry    内嵌 ZooKeeper 进程
├── payment-member      ┐
├── payment-account     │
├── payment-accounting  │
├── payment-trade       │  11 个业务域进程
├── payment-channel     │  （各自 schema.sql + MyBatis 注解 Mapper）
├── payment-risk        │
├── payment-cashier     │
├── payment-payment     │
├── payment-settle      │
├── payment-notify      │
├── payment-recon       ┘
└── payment-gateway     HTTP 网关 + 控制台
```

> v1.8 新增包/资源：payment-payment 含 `mq/`（MqConfig/MqProperties/OutboxPublisher/RocketMqOutboxPublisher/OutboxMessageListener，docker RocketMQ 主投递）；payment-risk 含 `service/RateLimiter` 双实现（InMemoryRateLimiter / RedisRateLimiter）；payment-gateway 含 `web/MerchantCache`（Redis 商户缓存）；payment-common 含 `db/H2Sequence`。各有库域带 `schema.sql`（local H2）+ `schema-mysql.sql`（docker MySQL）+ `application-docker.yml`，详见 §18。

### 3.2 依赖规则

```
业务域 ──▶ payment-facade ──▶ payment-common ──▶ tcc-core
业务域 ──▶ tcc-core（直接依赖，TccAutoConfiguration 自动装配模板 Bean）
payment-facade ──▶ tcc-core（TCC 契约直接使用组件 TccBranchRequest DTO）
tcc-core ──▶ 仅 spring-jdbc / spring-tx / spring-boot-autoconfigure / jackson / slf4j / lombok(provided)
```

- **facade 只含接口与 DTO**，消费方只依赖契约，不依赖实现（Dubbo 的标准工程实践）。
- **tcc-core 是零业务耦合的通用组件**：包名 `com.qoder.tcc`，不依赖 payment-common/payment-facade/Dubbo；异常用自带 `TccException`（CONFLICT/BIZ_ERROR 两类），参与者 SPI `TccParticipant` 传输无关（方法直接抛异常），通过 `TccAutoConfiguration`（AutoConfiguration.imports 注册）自动装配。任何 Spring Boot + JDBC 项目引入即可获得 TCC 与幂等能力（接入方式见 §6.7）。
- **业务模块直接依赖 tcc-core，无中间适配模块**（2026-11-17 移除 payment-dtx）：协调器/参与者模板/幂等模板一律直接用组件类；`TccException → Result` 错误码映射（CONFLICT→A0409、BIZ_ERROR→B0001）统一下沉到 `FacadeSupport`（payment-common）Dubbo 边界；仅存的 Dubbo 桥接 `DubboTccParticipant`（facade Result.orThrow → 组件 SPI，组合而非继承）放在协调器宿主 payment-payment 的 config 包。
- **版本集中管理**：parent 继承 `spring-boot-starter-parent:3.2.5`，import `dubbo-bom:3.2.14`，统一 MyBatis starter 3.0.3、ZooKeeper 3.8.4；内部模块版本走 `dependencyManagement`。
- **日志冲突治理**：dubbo-zookeeper-curator5 与 zookeeper 依赖显式 exclusion 掉 log4j/reload4j/slf4j-log4j12/logback（ZK 侧），全系统统一 Logback。
- **构建**：Maven Wrapper（`./mvnw`），Java 17（`maven.compiler.release=17`），boot 插件 repackage 成可执行 fat jar。

### 3.3 一个工程细节：HealthFacadeImpl 的归属

健康探针实现放在 **payment-facade** 模块的 `com.qoder.payment.common.health` 包下（而不是 common），原因：
- 实现需要 `@DubboService` 注解 → 依赖 dubbo；若放 common 则 common→facade→common 形成循环依赖。
- 各应用 `@EnableDubbo(scanBasePackages={"com.qoder.payment.<domain>", "com.qoder.payment.common"})` 双包扫描即可导出。
- `@DubboService(group = "${spring.application.name}")` 用占位符按应用名分组，11 个域共用一份实现、各自注册独立 group，网关按 group 精确引用做逐域探活。

---

## 4. 服务通信与治理

### 4.1 Dubbo 配置基线（所有域一致）

```yaml
dubbo:
  registry:
    address: zookeeper://127.0.0.1:${ZK_PORT:2181}
  protocol:
    name: dubbo
    port: ${<NAME>_DUBBO_PORT:2088x}
  provider:
    timeout: 5000
    retries: 0          # 关键：写操作绝不自动重试，重试权交给业务幂等层
  consumer:
    check: false        # 启动不强制依赖在线，支持任意启动顺序
    timeout: 5000       # 网关消费端为 8000（聚合查询略宽）
    retries: 0
  application:
    qos-enable: false
```

**`retries: 0` 是刻意设计**：Dubbo 默认失败重试 2 次，对支付这类写操作会造成重复请求放大。本系统把"重试"上移到两个受控位置——TCC 补偿调度（带分支幂等）与 outbox 投递（带接收端幂等），RPC 层本身零重试。

### 4.2 统一返回契约

所有 facade 方法返回 `Result<T>`：

```java
{ code: "00000", message: "成功", data: T }
```

| ResultCode | 含义 | 网关 HTTP 映射 |
|---|---|---|
| 00000 SUCCESS | 成功 | 200 |
| A0400 PARAM_ERROR | 参数错误（用户侧） | 400 |
| A0404 NOT_FOUND | 资源不存在 | 404 |
| A0409 CONFLICT | 状态冲突/重复请求 | 409 |
| B0001 BIZ_ERROR | 业务规则拒绝 | 400 |
| B0002 SYSTEM_ERROR | 系统内部错误 | 500 |
| C0001 DOWNSTREAM_ERROR | 下游不可达 | 502 |

- 错误码三段式（A 用户端 / B 本系统 / C 第三方）对齐阿里《Java 开发手册》错误码规范。
- 消费端统一 `result.orThrow()`：非 00000 转 `BizException` 抛出，业务代码不写 if-code 判断。
- 网关 `GlobalExceptionHandler` 兜底：`BizException`→按码映射；`RpcException`→503 C0001；非法 JSON→400；未知路由→404；其余→500。**任何异常都不会以堆栈形式泄漏给客户端**。

### 4.3 全链路追踪

- 网关 `TraceWebFilter`：取 `X-Trace-Id` 头（无则生成 UUID）→ 写入 SLF4J MDC（`traceId`）+ `RpcContext.getClientAttachment()`。
- Dubbo 隐式传参把 traceId 带到下游；各域 `TraceFilter`（Dubbo Filter SPI）在 provider 侧还原 MDC。
- 所有日志 pattern 携带 traceId，一次支付跨 6 个进程的日志可用同一 traceId 串联。

### 4.4 健康治理

- 每域导出 `HealthFacade.ping()`（group=应用名）。
- 网关 `HealthService` 聚合：gateway 自身 + registry + 11 域 Dubbo ping（timeout 1500ms），任一失败标红但不影响其余。registry 探针按 profile 分支：local 走内嵌 ZK 健康 HTTP `8092/health`；docker 走 Docker ZK 四字命令 `ruok`→`imok`（TCP 2181，800ms 超时）。
- `run.sh` 启动后轮询聚合健康直至 13/13 才宣告就绪。

---

## 5. 数据架构

### 5.1 存储选型

- **local profile（零依赖默认）**：H2 2.x **文件模式**，每域一个库：`jdbc:h2:file:${DATA_DIR:./data}/<domain>;MODE=MySQL;DATABASE_TO_LOWER=TRUE`
  - `MODE=MySQL`：SQL 方言贴近生产 MySQL，迁移成本低。
  - `DATABASE_TO_LOWER=TRUE`：标识符小写，与 MyBatis 驼峰映射（`map-underscore-to-camel-case`）配合。
  - 注意：H2 2.x 要求相对路径显式 `./` 前缀（隐式相对路径报 90011）。
- 建表：local 各域 `schema.sql`（`CREATE TABLE IF NOT EXISTS` + `CREATE SEQUENCE IF NOT EXISTS`），Spring SQL init 幂等执行，重启不丢数据；docker 各域 `schema-mysql.sql`（InnoDB/utf8mb4，`sql.init.mode=never` 一次性手动建表，序列改用 `sequences` 表 + 存储函数，见 §18.2）。
- ID 生成：统一 `SELECT next_value_for('seq_x')` 取号（local 经 H2 `CREATE ALIAS` 桥接原生序列 `H2Sequence.next`，docker 走 MySQL `sequences` 表 + 存储函数，见 §18.2）+ 业务前缀（P 支付 / R 退款 / O 订单 / N 通知 / S 结算 / RC 对账 / E 分录 / T 全局事务），全局可读、无中心发号器依赖。

> **Docker profile（MySQL）**：上述 H2 为 `local` 零依赖默认。`docker` profile 改用共享 MySQL 单实例多库 `pp_<domain>`（9 域），mapper SQL 不变——序列取号统一走 `SELECT next_value_for('seq_x')`：H2 由 `CREATE ALIAS` 桥接 `H2Sequence.next`，MySQL 由 `sequences` 表 + 存储函数（`LAST_INSERT_ID`）实现，两端同构。详见 [§18](#18-docker-中间件集成mysqlrocketmqrediszookeeper)。

### 5.2 支付核心库表（payment）

```sql
payment            -- 支付单：request_id UNIQUE（幂等键）、xid（关联全局事务）、
                   -- biz_type(TRADE/TOPUP/WITHDRAW，v1.7)、
                   -- status PROCESSING/SUCCESS/FAILED/REFUNDED、refunded_amount/refunded_fee
refund_record      -- 退款单：request_id UNIQUE、xid、biz_no（账务转账号）
outbox             -- 本地消息表：event/biz_id/payload/status(PENDING/DELIVERED/DEAD)/
                   -- attempts/next_retry_at/last_error；索引 (status,next_retry_at)
dtx_transaction    -- 全局事务日志：xid PK、(biz_type,biz_no) UNIQUE、status、
                   -- retry_count/next_retry_at/version；索引 (status,next_retry_at)
dtx_branch         -- 分支日志：(xid,branch_id) UNIQUE、participant/action/status/context/seq
```

> 交易域 `trade_order` 同样含 `biz_type` 与 `out_trade_no`（同商户幂等，UNIQUE idx_order_out_trade），字段级 DDL 见 [附录 C.4](#c4-trade-库payment-trade)。

### 5.3 参与者侧公共表（accounting / channel / trade 各自库内）

```sql
tcc_branch_log     -- PK (xid, branch_id, action)：TCC 幂等/空回滚/防悬挂的载体
idempotent_record  -- PK (biz_type, biz_no)：业务幂等占位 + 结果 JSON 回填
```

### 5.4 账务库表（accounting）

```sql
account_balance    -- account_id PK、balance（贷-借累计）、frozen、version
ledger_entry       -- 分录：seq 序列、biz_no、account_id、dir(D/C)、amount、balance_after
transfer_record    -- 转账凭证：biz_no PK（过账幂等兜底）、lines JSON、reversed 标记
```

> 字段级完整 DDL 见 [附录 C](#附录-c数据字典字段级-ddl)。

### 5.5 数据一致性边界

- **单域内**：本地事务保证（Spring `TransactionTemplate` / MyBatis 同库）。
- **跨域**：TCC（资金/订单/渠道的强一致预留）+ outbox（结算/通知的最终一致）。
- **绝不**出现跨库 JOIN 或跨域直连数据库。

---

## 6. 分布式事务设计（XTS 风格 TCC）

### 6.1 角色与部署

```
payment-payment（协调器宿主）
  TccCoordinator（tcc-core 组件，PaymentConfig 直接装配）
       │ ── dtx_transaction / dtx_branch（本地库）
       │ Dubbo: TccParticipantFacade.tryAction/confirm/cancel
       │ （DubboTccParticipant 桥接为组件 SPI TccParticipant）
       ├──▶ accounting 参与者（冻结/过账/解冻）
       ├──▶ channel 参与者（预授权/扣款/撤销）
       └──▶ trade 参与者（订单预留/标记已付/释放）
```

参与者通过 `TccParticipants` 配置注册为 `Map<String, TccParticipantFacade>`（key=participant 名），payment-payment 的 `DubboTccParticipant.wrap()` 转为组件 SPI Map 注入协调器，协调器按分支日志中的名字路由——新增参与者只需注册 bean + registerBranch，协调器零改动。

### 6.2 全局事务状态机

```
                 tryAll 全部成功
   TRYING ────────────────────────▶ CONFIRMING ──全部 Confirm 成功──▶ CONFIRMED（终态）
      │                                  │ 失败：退避补偿重试
      │ 任一 Try 失败 / 恢复判定回滚        ▼
      └──────────────▶ CANCELLING ──全部 Cancel 成功──▶ CANCELLED（终态）
                           │ 失败：退避补偿重试
                           ▼
              重试耗尽（8 次）──▶ DEAD（终态，待人工/对账介入）
```

- `begin(bizType, bizNo)`：xid = "T"+UUID；`(biz_type,biz_no)` 唯一约束兜底——同一支付单绝不可能开启两个全局事务。
- `registerBranch`：branchId = xid+"-B"+seq，context 序列化为 JSON 落库。**分支上下文持久化**意味着协调器重启后仍能原样重放二阶段，不依赖内存。
- `tryAll`：按注册顺序同步执行；任一失败 → 记录 fail_reason → 置 CANCELLING → 立即 driveCancel → 向调用方抛业务异常。
- `commitAsync`：置 CONFIRMING → 立即 driveConfirm 一次 → 未终态则登记下次重试时间。**接口不等待二阶段完成**。

### 6.3 二阶段驱动与事务性终态

`driveConfirm` 的关键设计（`tcc-core/.../TccCoordinator.java:136`）：

```java
for (branch : branches) {
    if (CONFIRMED) continue;              // 分支级断点续传
    if (status != TRY_OK) throw ...;      // 未就绪分支不允许提交
    participant.confirm(...);             // 失败 → return，留给补偿
    updateBranch(CONFIRMED);
}
tx.executeWithoutResult(s -> {            // ★ 同一个本地事务
    updateGlobal(xid, CONFIRMED);         //   全局终态
    listener.onConfirmed(xid);            //   业务终态 + outbox 写入
});
```

**全局终态、支付单状态翻转、outbox 消息三者原子提交**——这是事务性 outbox 的根基：不存在"事务已提交但消息丢失"或"消息已发但状态未落"的中间态。`driveCancel` 对称处理（onCancelled 落 FAILED + fail_reason）。

### 6.4 补偿恢复（recover）

`PaymentScheduler` 每 5s 调用 `coordinator.recover()`：

```sql
SELECT xid,status FROM dtx_transaction
WHERE status IN ('TRYING','CONFIRMING','CANCELLING') AND next_retry_at <= now
```

| 恢复时状态 | 决策 |
|---|---|
| CONFIRMING | 继续 driveConfirm（已 CONFIRMED 分支跳过） |
| CANCELLING | 继续 driveCancel（已 CANCELLED 分支跳过） |
| TRYING | 协调器在一阶段崩溃：全部 TRY_OK → 提交；否则 → 回滚 |

退避序列 `{5,10,20,40,80,160,320,640}s`，`MAX_RETRY=8`（累计约 21 分钟），耗尽置 **DEAD** 并 error 日志——不无限重试，把"无法自动收敛"的事务显式暴露给对账与人工。

### 6.5 参与者模板：三大经典问题的解法

`tcc_branch_log` 主键 `(xid, branch_id, action)`，日志与业务动作在**同一个 REQUIRES_NEW 本地事务**内提交（`tcc-core/.../TccParticipantTemplate.java`）：

**① 幂等**
```
Try:     INSERT log(TRYING) 主键冲突 → 已有记录，直接返回（不重复执行业务）
Confirm: status=CONFIRMED → 直接返回
Cancel:  status=CANCELLED → 直接返回
```

**② 空回滚**（Cancel 先于 Try 到达：Try 请求超时/丢失，协调器已决定回滚）
```
Cancel: 查无日志 → INSERT log(CANCELLED) 标记，不执行任何业务，返回成功
```

**③ 防悬挂**（Try 晚于 Cancel 到达：被空回滚过的分支，迟到的 Try 不能再预留资源）
```
Try: INSERT 冲突且既有 status=CANCELLED → 抛 CONFLICT 拒绝
```

**④ Try 业务失败的日志清理**
```
Try: 业务抛异常 → 同事务内 DELETE 日志 → 后续 Cancel 走空回滚
```
若不清理，会留下 TRYING 僵尸日志，Cancel 时误执行解冻/撤销。删除与业务失败同事务回滚语义一致：**未预留资源 = 无日志**。

**⑤ 状态互斥保护**
```
Confirm 遇 CANCELLED → 抛错（已回滚分支不可提交）
Cancel  遇 CONFIRMED → 抛错（已提交分支不可回滚）
Confirm 无 Try 记录  → 抛错（不允许凭空提交）
```

### 6.6 崩溃恢复正确性矩阵

| 崩溃点 | 恢复行为 | 依据 |
|---|---|---|
| Try 执行中（协调器宕机） | recover 见 TRYING：非全 TRY_OK → 回滚 | 分支日志持久化 |
| Try 全成功、未及 commit | recover 见 TRYING：全 TRY_OK → 提交 | 同上 |
| Confirm 部分成功 | recover 续驱，跳过 CONFIRMED 分支 | 分支级断点续传 |
| Confirm 成功、终态事务未提交 | 参与者幂等跳过，终态事务重做 | 参与者幂等 + 本地事务原子性 |
| 参与者宕机（如渠道进程被杀） | Confirm/Cancel RPC 失败 → 退避重试至其恢复 | 混沌测试 [C] 实测验证 |
| 终态提交后 outbox 未投递 | outbox 调度续投 | 消息与终态同事务，绝不丢失 |

### 6.7 组件化：tcc-core 通用组件与接入方式

TCC 框架为**零业务耦合**的通用组件 `tcc-core`（包名 `com.qoder.tcc`），事务语义、重试参数、表结构、日志与组件化前完全一致。演进：先从 payment-dtx 抽取为通用组件（保留薄适配层），2026-11-17 进一步**整体移除 payment-dtx 模块**，业务模块直接依赖 tcc-core：

```
tcc-core（com.qoder.tcc，13 个类）
├── TccCoordinator          协调器：begin/registerBranch/tryAll/commitAsync/rollbackAsync/recover
├── TccParticipantTemplate  参与者模板：幂等/空回滚/防悬挂（REQUIRES_NEW）
├── IdempotentTemplate      通用幂等模板（占位-执行-回填同事务）
├── TccParticipant          参与者 SPI（传输无关，方法直接抛异常，不包 RPC 返回体）
├── TccBranchRequest        通用分支请求 DTO（xid/branchId/action/context）
├── TccException            通用异常：CONFLICT（冲突）/ BIZ_ERROR（业务错误）两类
├── TccCompletionListener   终态回调（与终态落库同一本地事务，支撑事务性 outbox）
├── TccContextUtil          上下文 JSON 序列化
├── TccSchema               建表 DDL 常量（幂等 IF NOT EXISTS，不破坏既有数据）
├── GlobalStatus / BranchStatus / ParticipantStatus   状态枚举
└── TccAutoConfiguration    自动装配（AutoConfiguration.imports 注册；@ConditionalOnSingleCandidate(DataSource)，无数据源应用自动跳过）
```

**三处业务耦合的替代**：
1. `BizException/ResultCode` → 组件自带 `TccException`（CONFLICT/BIZ_ERROR 语义等价），`TccException → Result` 错误码映射（CONFLICT→A0409、BIZ_ERROR→B0001，其余码透传）统一在 `FacadeSupport`（payment-common）Dubbo 边界完成，对外错误码与组件化前完全一致；
2. facade 的 `TccBranchRequest` → **契约直接使用组件通用 DTO**（`com.qoder.tcc.TccBranchRequest`，Serializable，字段同构，facade 侧旧 DTO 已删除，无 DTO 转换层）；
3. `TccParticipantFacade`（Result + orThrow）→ 传输无关 SPI `TccParticipant`（直接抛异常），桥接类 `DubboTccParticipant` 位于协调器宿主 payment-payment 的 config 包（组合实现 SPI，非继承）。

**直依赖后的装配**：六个使用模块（payment/accounting/channel/trade/settle/notify）pom 直接依赖 tcc-core；`TccParticipantTemplate`/`IdempotentTemplate` Bean 由组件 `TccAutoConfiguration` 自动装配（不再有适配层 Bean 覆盖）；协调器由 `PaymentConfig` 直接 `new TccCoordinator(jdbc, txManager, DubboTccParticipant.wrap(participants.asMap()), listener)`。原 payment-dtx 的 7 个类中，3 个继承薄包装与 `DtxAutoConfiguration`/`TccExceptions`/`TccRequests` 随模块删除，仅 `DubboTccParticipant` 迁入 payment-payment。

**其他项目接入方式**（任意 Spring Boot 3 + JDBC 项目，无需 Dubbo/ZooKeeper）：

```xml
<!-- ① 依赖坐标（安装到本地/私服后引用） -->
<dependency>
    <groupId>com.qoder.payment</groupId>
    <artifactId>tcc-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

```sql
-- ② 建表：协调器库执行 TccSchema.COORDINATOR（dtx_transaction/dtx_branch），
--    参与者库执行 TccSchema.PARTICIPANT（tcc_branch_log），
--    用幂等模板的库执行 TccSchema.IDEMPOTENT（idempotent_record）。
--    全部 IF NOT EXISTS，可纳入各自 schema.sql，重启不破坏数据。
```

```java
// ③ 自动装配：引入依赖即获得 TccParticipantTemplate/IdempotentTemplate Bean
//    （TccAutoConfiguration 经 AutoConfiguration.imports 注册，无需任何注解）。
// ④ 实现 SPI：每个参与者实现 com.qoder.tcc.TccParticipant（tryAction/confirm/cancel，
//    内部用 TccParticipantTemplate 包裹业务动作），按 participant 名注册为 Map Bean。
// ⑤ 组装协调器（协调器宿主进程）：
@Bean
public TccCoordinator tccCoordinator(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                     Map<String, TccParticipant> participants,
                                     ObjectProvider<TccCompletionListener> listener) {
    return new TccCoordinator(jdbc, txManager, participants, listener.getIfAvailable());
}
// ⑥ 定时调用 coordinator.recover()（如 @Scheduled(fixedDelay=5000)）驱动异步补偿。
// 异常处理：组件抛 TccException（CONFLICT/BIZ_ERROR），接入方可在边界映射为自身错误码体系。
```

---

## 7. 幂等体系设计

幂等不是单点机制，而是**五层防线**，每层解决不同粒度的重复：

### 7.1 第一层：入口 requestId（支付/退款）

```
pay(request):
  1. requireRequestId            -- 幂等键必填，缺失 400
  2. selectByRequestId 预检       -- ★ 回放先于一切状态检查
  3. 订单/账户/余额校验
  4. INSERT payment (request_id UNIQUE)
     └ DuplicateKeyException → selectByRequestId → 返回原结果(repeated=true)
```

两个关键决策：
- **先插占位再执行**（insert-first）：并发同 requestId 请求在唯一键上竞争，败者直接读胜者结果。相比"先查后插"，消除了查-插窗口期的竞态。
- **预检先于状态检查**：订单已 PAID、支付单已 REFUNDED 之后，同 requestId 回放仍必须返回**原始结果**而非 409。幂等的语义是"同一请求得到同一响应"，与当前业务状态无关。（这是实测修复过的真实缺陷。）

### 7.2 第二层：IdempotentTemplate（通用业务幂等）

用于充值（TOPUP/bizNo）、通用过账（POST/bizNo，v1.3 转账/提现）、结算登记（SETTLE_PAY/paymentId、SETTLE_REFUND/bizId）、退款标记（REFUND_MARK/bizNo）、通知登记（NOTIFY/event:bizId）：

```java
tx.execute(s -> {
    INSERT idempotent_record(biz_type, biz_no, result=NULL)   // 占位
    // DuplicateKey → 读既有 result：
    //   result 非空 → 反序列化直接返回（结果一致）
    //   result 为空 → 首请求仍在处理 → CONFLICT "处理中"
    result = action.get();                                   // 业务
    UPDATE result = toJson(result);                          // 回填
    return result;                                           // 同一事务提交
});
```

占位、业务、回填同事务：并发重复请求要么命中完整结果，要么得到明确的"处理中"，**绝不会重复执行业务**。

### 7.3 第三层：TCC 分支幂等

见 §6.5——补偿重试、网络重发、协调器重启重放，全部被 `(xid,branch_id,action)` 主键吸收。

### 7.4 第四层：CAS 状态机（并发竞态）

所有状态流转带旧态条件：

```sql
-- 订单预留：只有一个支付请求能把 CREATED 改成 PAYING
UPDATE trade_order SET status='PAYING', payment_id=? WHERE id=? AND status='CREATED'
-- 渠道扣款：PREAUTH 只能翻转一次
UPDATE channel_trade SET status='CAPTURED' WHERE biz_no=? AND status='PREAUTH'
```

8 线程不同 requestId 支付同一订单：恰好 1 个 SUCCESS，其余 Try 失败 → TCC 整体回滚 → FAILED，余额只扣一次。（混沌测试 [B] 实测验证。）

### 7.5 第五层：接收端幂等（消息语义闭环）

outbox 至少一次投递 → settle/notify 用 IdempotentTemplate 按 bizId 去重 → 重复消息无副作用。**至少一次 + 接收端幂等 = 恰好一次效果**。

### 7.6 账务过账幂等兜底

`transfer.biz_no UNIQUE`：即使上游全部失效，同一 bizNo 的转账凭证也不会重复落库（post 内捕获 DuplicateKey 记日志）。

---

## 8. 异步消息与可靠投递

### 8.1 事务性 outbox（支付核心侧）

**生产**：`PaymentCompletionListener.onConfirmed` 与全局终态同事务写入：

| event | biz_id | 载荷 | 消费方 |
|---|---|---|---|
| SETTLE_PAY | paymentId | SettlePayRequest | settle.recordPay |
| SETTLE_REFUND | refundId | SettleRefundRequest | settle.recordRefund |
| NOTIFY_PAY | paymentId | NotifyRequest(PAY_SUCCESS) | notify.send |
| NOTIFY_REFUND | refundId | NotifyRequest(REFUND_SUCCESS) | notify.send |

**投递**：`OutboxDispatcher`（每 3s，批量 50）：
- 成功 → DELIVERED；
- 失败 → attempts+1，退避 `{3,6,12,24,48,96,192,384}s`，`MAX_ATTEMPTS=8`；
- 耗尽 → **DEAD** + error 日志，等待对账暴露（对账"支付-清结算一致"项会精确报出缺失笔数）。

> **Docker profile（RocketMQ 主投递）**：`docker` 模式下 outbox 行同事务落库后，`afterCommit` 同步 publish 到 `PP_OUTBOX`（tag=event、key=bizId），payment-payment 内的消费者收到即调用**同一套幂等 Dubbo 投递**（`deliverByEventBizId`）并 markDelivered——MQ 是主路径，3s 轮询退化为兜底。MQ 模式 insert 时 `next_retry_at=now+4000ms`（grace），使轮询只补 MQ 尚未送达的行；消费异常 `RECONSUME_LATER` 由 RocketMQ 重投。`local` 模式 MQ 关闭，纯轮询，零依赖。详见 [§18.3](#183-rocketmq-主投递--outbox-兜底)。

### 8.2 商户通知（notify 域）

登记（幂等）→ 状态机：

```
无 callbackUrl ──▶ PULL（商户主动补查）
有 callbackUrl ──▶ PENDING ──HTTP POST JSON──▶ 2xx → DELIVERED
                     │ 非2xx/异常
                     ▼
              attempts<5 → PENDING + next_retry_at（退避 {5,10,20,40,80}s）
              attempts≥5 → FAILED（重试耗尽，商户仍可 PULL 补查）
```

- `NotifyPusher` 每 3s 扫描到期 PENDING，JDK HttpClient（连接/请求各 3s 超时）。
- lastError 记录异常类名+消息（`describe()` 兜底 null message，如 macOS ConnectException）。
- 送达/耗尽均有日志；FAILED 不是数据丢失——通知记录永久可查。

### 8.3 双调度总览（payment-payment）

```java
@Scheduled(fixedDelay = 5000) recoverTransactions();  // TCC 二阶段补偿
@Scheduled(fixedDelay = 3000) dispatchOutbox();       // 消息可靠投递
```

`fixedDelay` 保证上一轮结束才计时下一轮，单实例内无重叠执行。

---

## 9. 各领域详细设计

### 9.1 会员域（member）

- 类型 USER / MERCHANT；注册唯一性（重名 409）；状态 ACTIVE / FROZEN。
- 商户持有 `appId/appSecret`（OpenAPI 验签凭据）。
- 下单校验：必须 MERCHANT 且 ACTIVE（USER 下单 400，冻结商户 400）。

### 9.2 账户域（account）

- 账户实体：id、memberId、type（USER/MERCHANT）、status。
- **不存余额**——余额权威在账务域，账户域只做实体与状态（冻结校验）。
- `check(ids)` 批量状态接口供账务域过账前校验，避免 N 次 RPC。

### 9.3 账务域（accounting）——资金正确性核心

详见 §11。要点：
- `balance = 贷方累计 − 借方累计`；`可用 = balance − frozen`。
- 系统户（`SYS_` 前缀：SYS_CASH 银行现金、SYS_CLEARING 清算中转、SYS_FEE 手续费收入）允许透支；非系统户借方必须足额可用。
- **排序加锁**：多账户转账按 accountId 排序后逐个 `SELECT FOR UPDATE`，全局一致的加锁顺序 → 无死锁。
- `lockOrCreate`：首笔业务懒开户，并发 INSERT 竞争捕获 DuplicateKey 后加锁重读。
- TCC 三动作：Try=freeze（只冻借方非系统户）；Confirm=**先解冻再过账**（post 的可用余额校验不受本笔冻结影响——否则全额退款时冻结额≥余额一半会误报"可用余额不足"，实测修复）；Cancel=unfreeze（`Math.max(0, frozen-amount)` 防御）。
- `post`：校验借贷平衡 → 排序加锁 → 逐行更新余额 + 落分录（含 balance_after 快照）→ 落转账凭证（bizNo 幂等）。
- `trial`：全量借方合计=贷方合计、所有账户余额总和=0（封闭系统资金守恒）。

### 9.4 交易域（trade）

状态机（全部 CAS）：

```
CREATED ──reserve──▶ PAYING ──markPaid──▶ PAID ──refundMark──▶ PARTIAL_REFUNDED ──▶ REFUNDED
   │                    │
   close                release（Cancel，校验 payment_id 匹配）
   ▼                    ▼
 CLOSED              CREATED
```

- `reserve`：已 PAID/REFUNDED → 409"已支付"；CLOSED → 409；CAS 失败 → 409"正在支付中"。
- `release` 带 paymentId 条件：只释放**本支付单**的预留，防止误释放他人预留。
- `markPaid` 幂等：已 PAID 直接返回。
- `refundMark`：IdempotentTemplate(REFUND_MARK, bizNo)；累计退款额校验；`refunded==amount → REFUNDED，否则 PARTIAL_REFUNDED`。
- `checkRefundable`（REFUND Try）：只校验不落状态——退款预留由账务冻结承载。

### 9.5 支付核心（payment）——编排中枢

`pay()` 完整流水线：

```
requestId 必填 → 幂等预检 → method 校验(BALANCE/CARD)
→ 订单 CREATED 校验 → 商户账户定位
→ BALANCE: 付款账户 ACTIVE + 余额足额 / CARD: 卡号必填
→ 计费 fee = MoneyUtil.fee(amount, feeBps)   // 默认 60bps = 0.6%
→ INSERT payment(PROCESSING, request_id UNIQUE, biz_type=TRADE)
→ 风控 check（拒绝 → payment FAILED 留痕 + 抛错）
→ coordinator.begin("PAY", paymentId) + updateXid
→ registerBranch: accounting(PAY) → [channel(PAY) 仅 CARD] → trade(PAY)
→ tryAll（同步，失败整体回滚并抛错）
→ commitAsync（异步二阶段）
→ 立即返回 PROCESSING，客户端轮询终态
```

`refund()` 对称，额外规则：
- 仅 SUCCESS 可退；累计可退余额校验；
- **手续费按比例退**：`feeRefund = (fee*amount + total/2) / total`（四舍五入）；**最后一笔退款退剩余全部手续费**（`fee − refundedFee`），保证分账精确闭环、无一分钱残留；
- 支付单终态翻转在 SQL 内原子完成：`status = CASE WHEN refunded_amount >= amount THEN 'REFUNDED' ELSE status END`。

分支注册顺序 accounting → channel → trade 的考量：资金冻结最先（最可能失败的资源约束前置），渠道次之，订单预留最后（最轻量）。Try 失败时回滚成本最小化。

**`fundPay()` 资金单（v1.7）**：充值/提现与商户交易同构——每笔资金操作生成一笔交易单 + 一笔支付单。流水线：bizType 校验（TOPUP/WITHDRAW）→ requestId 回放 → `ensureFundMerchant()`（synchronized 懒建系统资金商户 SYS_FUND + 账户）→ WITHDRAW 可用余额预检 → `tradeFacade.createOrder`（outTradeNo=requestId、bizType 透传）→ INSERT payment（**fee=0、method=BALANCE、biz_type=TOPUP/WITHDRAW**）→ `begin("PAY")` + 账务/交易两分支（**无渠道分支**）→ tryAll/commitAsync。账务 bizNo=支付单 id、refId=交易单 id：TOPUP 借 SYS_CASH 贷用户，WITHDRAW 借用户贷 SYS_CASH。完结复用通用 `PaymentCompletionListener`（写 SETTLE_PAY/NOTIFY_PAY outbox），故资金单天然过五方对账，ReconService 零改动。详见 §17.1。

### 9.6 收银台（cashier）

- `cashier(orderId)`：聚合订单 + 可用支付方式（BALANCE 附 USER 活跃账户列表；CARD 附提示）。
- `pay`：透传支付核心（收银台只做渲染与受理，不做业务决策）。

### 9.7 渠道网关（channel）

模拟银行，状态机 CAS：

```
PAY:    PREAUTH ──capture──▶ CAPTURED      REFUND: PREAUTH ──capture──▶ REFUNDED
            └──voidAuth──▶ VOIDED（Cancel）
```

- 拒绝规则：卡号尾号 0000 → Try 阶段抛"渠道拒绝: 银行风控拦截"（模拟银行侧风控）。
- REFUND Try 校验原交易必须 CAPTURED（未扣款不可退）。
- 渠道是独立进程 → 可被单独停掉做混沌演练，TCC 补偿在其恢复后自动收敛。

### 9.8 风控（risk）

简化规则引擎，`RiskService` 注入 `RateLimiter` 抽象，频率限制双实现按 `payment.risk.redis.enabled` 切换（语义一致）：
1. 单笔限额 10,000,000 分（10 万元）；
2. 同一 payerId 10s 内 ≤5 笔（滑动窗口）：
   - **local**：`InMemoryRateLimiter`（`ConcurrentHashMap<String, Deque<Long>>` + 窗口锁，`matchIfMissing=true` 默认）；
   - **docker**：`RedisRateLimiter`（Lua 原子脚本对 ZSET `pp:risk:window:<payer>` 做 ZREMRANGEBYSCORE→ZCARD→ZADD+PEXPIRE，见 §18.4）。

payerId：BALANCE=accountId；CARD="CARD:"+cardNo（卡维度独立限频）。拒绝结果带 reason，支付核心落 FAILED 留痕。

### 9.9 清结算（settle）

- `recordPay/recordRefund`：IdempotentTemplate 去重；REFUND 必须能找到原 PAY 结算单（乱序消息防护——若 PAY 消息未到，REFUND 投递失败进入退避，天然等待 PAY 先达）。
- 结算单：gross（总额）/ fee（手续费）/ net（净额）三列。
- `summary` 口径（与账务恒等式对齐）：
  - totalGross = Σ PAY.gross；totalRefund = Σ REFUND.gross
  - **totalFee = Σ(PAY.fee) − Σ(REFUND.fee)**（净手续费 = SYS_FEE 余额）
  - **totalNet = Σ(PAY.net) − Σ(REFUND.net)**（净结算额 = 商户余额增量）

### 9.10 消息通知（notify）

见 §8.2。登记幂等键 `event:bizId`；PULL/PENDING/DELIVERED/FAILED 四态；记录永久可查（`records(bizId)` / `get(notifyId)`）。

### 9.11 对账（recon）

`run()` 六项核对，**逐项故障隔离**（任一域不可达只判该项失败并记录原因，不影响其余）：

| 核对项 | 规则 |
|---|---|
| 账务试算平衡 | D=C 且 Σbalance=0 |
| 支付单采集 | 拉取 SUCCESS 支付单（**PROCESSING 在途单不参与核对**，避免误报） |
| 支付-账务一致 | 每笔 SUCCESS 支付存在等额借方转账 |
| 支付-交易一致 | 订单状态 ∈ {PAID, PARTIAL_REFUNDED, REFUNDED} |
| 支付-清结算一致 | 每笔支付存在 PAY 结算单（缺失=outbox 在途或 DEAD） |
| 支付-渠道一致 | 每笔 CARD 支付存在 CAPTURED/REFUNDED 渠道交易 |

判定机制：detailAction 内统计缺失数 → failSignal 读计数判 pass（计数与明细同源，杜绝"明细说齐备、判定却失败"的不一致）。报告 JSON 落库，`latest()` 可查历史。

---

## 10. 核心流程时序

### 10.1 余额支付（成功路径）

```
客户端        网关      收银台     支付核心         账务         交易      outbox调度   结算/通知
  │ POST pay    │          │          │               │           │          │           │
  ├────────────▶├─────────▶├─────────▶│               │           │          │           │
  │             │          │          │ 幂等预检/校验/INSERT PROCESSING       │           │
  │             │          │          │ 风控 check     │           │          │           │
  │             │          │          │ begin+register │           │          │           │
  │             │          │          │──Try freeze───▶│ 冻结付款方  │          │           │
  │             │          │          │──Try reserve──────────────▶│ CREATED→PAYING       │
  │             │          │          │ commitAsync    │           │          │           │
  │             │          │          │──Confirm──────▶│ 解冻+过账   │          │           │
  │             │          │          │──Confirm──────────────────▶│ PAYING→PAID          │
  │             │          │          │ [同事务] CONFIRMED + payment SUCCESS + outbox×2    │
  │◀── PROCESSING（立即返回）──────────┤               │           │          │           │
  │ GET payments/{id} 轮询 …           │               │           │          │──3s──────▶│
  │◀── SUCCESS ───────────────────────┤               │           │          │  结算登记  │
  │             │          │          │               │           │          │  通知登记→推送商户
```

### 10.2 渠道拒绝（Try 失败回滚）

```
Try freeze ✓ → Try preauth ✗（尾号0000）
→ 账务分支 TRY_OK、渠道 TRY_FAIL、交易 INIT
→ CANCELLING → driveCancel：
    账务 Cancel：解冻（有日志，正常执行）
    渠道 Cancel：空回滚（Try 失败已删日志 → 落 CANCELLED 标记，不动业务）
    交易 Cancel：空回滚（从未 Try → 落标记）
→ [同事务] CANCELLED + payment FAILED(fail_reason)
→ 客户端收到 400 "风控拒绝/渠道拒绝"；订单仍 CREATED 可重新支付
```

### 10.3 渠道宕机（异步补偿收敛，混沌测试 [C]）

```
CARD 支付：Try 全部成功（渠道 Try 在宕机前完成）→ commitAsync
→ Confirm 渠道分支 RPC 失败 → CONFIRMING 挂起，next_retry_at 登记
→ 支付单保持 PROCESSING（在途，不参与对账）
→ 渠道进程恢复 → recover() 到期续驱 → Confirm 成功（参与者幂等保证不重复扣款）
→ CONFIRMED + SUCCESS + outbox → 结算/通知补齐 → 对账全通过
```

### 10.4 全停重启（持久化恢复，混沌测试 [D]）

```
stop.sh（SIGTERM→15s→SIGKILL 兜底）→ H2 文件库落盘
→ run.sh --no-build → 各域 schema IF NOT EXISTS 幂等建表
→ 未终态事务被 recover() 捡起续驱；未投递 outbox 被调度续投
→ 会员/支付单/余额与停机前快照完全一致
```

---

## 11. 账务模型

### 11.1 复式记账

每笔资金变动是一组**借贷平衡**的分录行（ΣD = ΣC，过账前强校验）。余额方向约定：`余额 = 贷方累计 − 借方累计`（负债类视角：客户余额是平台负债）。

### 11.2 标准分录

**充值**（bizNo 幂等）：
```
D SYS_CASH 10000    C 用户账户 10000
```

**余额支付**（amount=2990, fee=18, net=2972）：
```
D 付款账户 2990     C 商户账户 2972     C SYS_FEE 18
```

**银行卡支付**（银行现金流入，不走清算中转）：
```
D SYS_CASH 2990     C 商户账户 2972     C SYS_FEE 18
```

**退款**（反向，reversed=true；部分退按比例退手续费）：
```
D 商户账户 net'     D SYS_FEE feeRefund     C 付款账户(或SYS_CASH) amount
```

### 11.3 冻结语义（TCC Try）

- 只冻**借方非系统户**：付款方余额不动，`frozen += amount`，可用余额下降 → 并发支付/退款不会超支。
- 系统户不冻（可透支）。
- Confirm：**先解冻再过账**（顺序关键，见 §9.3）；Cancel：仅解冻。
- 冻结期间余额不变 → 对账"余额总和=0"在任意时刻成立（分录未落则余额未动）。

### 11.4 恒等式（对账与测试断言）

```
Σ借方 = Σ贷方                          （试算平衡）
Σ所有账户余额 = 0                       （封闭系统守恒）
SYS_FEE 余额 = Σ结算净手续费             （手续费闭环）
商户余额增量 = Σ结算净额                 （结算闭环）
```

---

## 12. 安全设计

### 12.1 OpenAPI 验签（网关 /openapi/*）

对齐支付宝开放平台签名机制：
1. 必填 `appId / sign / timestamp`；timestamp 与服务端时差 > ±300s → 拒绝（防重放）。
2. 经 `MerchantCache` 定位商户 appSecret：docker profile 先查 Redis `pp:openapi:app:<appId>`（TTL 60s，读写异常降级直查），未命中回源 `memberFacade.getByAppId(appId)`；local profile 直查 facade（见 §18.4）。
3. 参数按 key TreeMap 排序（剔除 sign、跳过 null）→ `k=v&k=v` 拼接 → **HmacSHA256(secret)** → hex 与 sign 比对，失败 400"签名验证失败"。
4. OpenAPI method 映射内部 facade（outTradeNo→requestId，BANKCARD→CARD 归一化）；端点全集与参数见 §16.2。

### 12.2 敏感信息

- 卡号出参脱敏：`622202****0202`（前 6 后 4，PaymentConverter.maskCard）；库内保留全卡号供渠道使用。
- appSecret 不出现在任何查询 DTO。
- 异常响应只含错误码+业务消息，不泄漏堆栈/SQL。

### 12.3 资金防线

- 金额全程 **long 型"分"**（MoneyUtil 校验正数与上限），杜绝浮点误差。
- 风控前置（限额+限频）；账户/商户冻结状态在下单、支付、过账三处重复校验（纵深防御）。
- 退款累计额校验在支付核心与交易域双重执行。

---

## 13. 可观测性与运维

### 13.1 日志

- 统一 Logback，pattern 含 traceId；关键动作（幂等命中、TCC 各阶段、outbox 投递、通知送达/耗尽、DEAD）均有结构化日志。
- 每进程独立日志文件 `logs/<name>.log`。

### 13.2 一键启停

**run.sh**：
```
JAVA_HOME 探测（corretto-18 优先，回退 java_home -v17）
→ ./mvnw package -DskipTests（--no-build 跳过）
→ registry 先启 + 等待 8092/health（60s）
→ 11 域 + gateway 逐个 nohup 启动（pid → run/<name>.pid，存活则跳过）
→ 等待 8080/api/health → 轮询聚合健康至 13/13（120×2s）
```

**stop.sh**（优雅停机）：
```
收集存活 pid → 全部 SIGTERM → 删除 pidfile → 等待 15s → 幸存者 SIGKILL
```
设计依据：先停注册中心会让其余服务的 Dubbo shutdown hook 阻塞在 unregister，必须有 SIGKILL 兜底；**data/ 目录永不删除**，重启数据完整。

**`run.sh --docker`**：切换 `docker` profile（各域 `-Dspring.profiles.active=docker`），加载仓库外 `~/.config/payment-pro/docker.env`（MySQL 口令等，600）；先探测 Docker ZooKeeper `127.0.0.1:2181` 可达（`/dev/tcp`，30s），**跳过内嵌 registry**，其余 11 域 + gateway 照常启动。`--no-build` 跳过编译。详见 [§18.6](#186-启停与只读数据迁移)。

### 13.3 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| DATA_DIR | ./data | H2 数据目录 |
| FEE_BPS | 60 | 手续费基点（0.6%） |
| ZK_PORT / ZK_HTTP_PORT | 2181 / 8092 | 注册中心 |
| GATEWAY_PORT | 8080 | HTTP 入口 |
| \<NAME\>_DUBBO_PORT | 20880~20891 | 各域 Dubbo 端口 |
| MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD | 127.0.0.1 / 3306 / root / — | docker profile MySQL（口令放仓库外 docker.env） |
| REDIS_HOST / REDIS_PORT | 127.0.0.1 / 6379 | docker profile 风控限流 + 网关缓存 |
| ROCKETMQ_NAMESRV | 127.0.0.1:9876 | docker profile outbox 主投递 |

### 13.4 控制台

网关内置单页控制台（static/index.html）：会员/账户/充值/下单/收银台支付/退款/渠道/结算/通知/对账/架构健康 12 个视图，全部走 /api/*，与 OpenAPI 共用同一套 facade。

---

## 14. 测试与验证体系

### 14.1 smoke-test.py（79 项断言，全部通过）

覆盖：健康聚合、会员/账户（重名 409、USER 下单 400）、充值幂等、余额支付全链路（终态轮询、requestId 回放、订单级重复 409）、部分/全额退款（手续费比例、终态翻转、回放幂等、超退 409）、银行卡（脱敏、SYS_CASH 分录、0000 拒绝留痕、订单可重付）、风控（超限/关闭订单/余额不足/冻结/限频）、通知（真实 HTTP 回调送达 + 死链退避重试）、OpenAPI（验签成功/错签/过期时间戳）、网关错误映射、对账六项 + 账务恒等式 + 结算口径。

### 14.2 verify-pro.py（22 项断言，全部通过）

| 场景 | 验证点 |
|---|---|
| [A] 8 线程同 requestId | 恰好 1 支付单，余额只扣一次 |
| [B] 8 线程同订单不同 requestId | 恰好 1 SUCCESS 其余 FAILED（TCC 回滚），订单 PAID |
| [C] 渠道宕机混沌 | CARD 支付挂起 PROCESSING → 渠道恢复 → 补偿收敛 SUCCESS → 对账通过 |
| [D] 全停重启 | H2 持久化：会员/支付单/余额快照一致，在途事务恢复，对账通过 |

### 14.3 浏览器实测

真实 UI 走通：注册→开户→充值→下单→收银台余额支付→全额退款→对账六项通过；架构页 13/13 健康、试算平衡。

---

## 15. 已知边界与演进方向

诚实列出当前实现的边界（均为约束下的刻意取舍，而非缺陷）：

| 边界 | 现状 | 生产演进 |
|---|---|---|
| 存储 | H2 单机文件库（local）；**docker profile 已落地 MySQL pp_* 多库** | 每域独立 MySQL 实例 / 分库分表 |
| 注册中心 | 内嵌单点 ZK（local）；**docker profile 已接共享 ZK 2181** | ZK 集群 / Nacos |
| 风控窗口 | 单进程内存（local）；**docker profile 已落地 Redis Lua 滑动窗口** | 规则引擎平台 |
| 消息投递 | outbox 轮询（local）；**docker profile 已落地 RocketMQ 主投递 + 轮询兜底** | 事务消息 / 多 topic 分流 |
| 调度器 | 单实例 @Scheduled | 多实例 + 分布式锁（ShedLock）或分片调度 |
| TCC 参与者路由 | 协调器内静态 Map | 参与者自注册 + 动态发现 |
| DEAD 事务 | 日志+对账暴露，人工介入 | 差错处理平台（自动冲正/挂账） |
| 渠道 | 模拟银行（0000 拒绝规则） | 真实银行/卡组织适配器，渠道侧异步回执+查询补单 |
| 通知安全 | 明文 HTTP POST | 通知体签名（商户验签）+ HTTPS |
| 对账 | 全量实时核对 | 日切批量对账 + 渠道对账文件解析 |
| 认证 | OpenAPI HMAC；控制台无登录 | 控制台 OAuth2/RBAC |

这些边界不影响当前系统的正确性论证：所有资金一致性机制（TCC、幂等、outbox、复式记账、对账）都是生产同构的，替换的只是基础设施载体。

---

## 16. OpenAPI 商户接入扩展（v1.3.0，ride-pro 双账本对接）

### 16.1 HTTPS 8443 双连接器

网关在 HTTP 8080（控制台/既有脚本不变）之外新增 HTTPS 8443：`HttpsConfig`（WebServerFactoryCustomizer 附加 Tomcat 连接器 + SSLHostConfig/PKCS12）。run.sh 首次启动自动 keytool 生成自签证书 `config/payment-pro.p12`（RSA2048，CN=localhost，SAN=dns:localhost,ip:127.0.0.1，3650 天）并导出公钥 `config/payment-pro-cert.pem` 供接入方显式信任；keystore 缺失时跳过 HTTPS 并告警，不影响 HTTP。两文件均 gitignore。

### 16.2 OpenAPI 新增端点（appId + HMAC-SHA256 验签，timestamp ±5min）

| 端点 | 语义 |
|---|---|
| account.open | 商户侧用户开户：name+type 幂等（查会员→缺则建→查账户→缺则开），返回 {memberId, accountId} |
| account.topup | 充值（AccountingFacade.topup，bizNo 幂等）——**v1.7.0 起 ride 镜像改用 fund.topup** |
| account.transfer | 通用两腿划转（from D / to C） |
| account.withdraw | 提现出金（D 账户 / C SYS_CASH）——**v1.7.0 起 ride 镜像改用 fund.withdraw** |
| account.balance | 余额查询（裸数字） |
| fund.topup | 充值资金单（交易单 TOPUP + 支付单，SYS_FUND/fee=0/BALANCE，requestId 幂等），返回 {payment:{id...}}（v1.7.0） |
| fund.withdraw | 提现资金单（交易单 WITHDRAW + 支付单，D 账户 / C SYS_CASH，requestId 幂等），返回 {payment:{id...}}（v1.7.0） |
| trade.refund | 标准退款（paymentId + amount + outRefundNo） |
| trade.query | 订单+支付单查询（商户 PULL 补查对账） |

trade.pay 增加可选 `callbackUrl`（异步通知投递地址）。账务侧新增 `AccountingFacade.post(PostRequest)`：通用过账以 IdempotentTemplate("POST", bizNo) 包裹，保证 bizNo 幂等（post 本体仅转账记录防重）。

### 16.3 ride-pro 双账本接入（对端视角）

ride-pro 以商户身份接入（integrate-payment.sh 注册 name=ride-pro 的 MERCHANT）：本地钱包仍是资金权威，每笔资金操作先落 PENDING 流水再**同步**调用 payment-pro，凭回执确认动账——TOPUP→fund.topup（资金单）；PAY→trade.create+trade.pay(BALANCE)+商户→司机分成划转；REFUND→按本地借贷腿划转（司机追回+商户退乘客，**不走 trade.refund**，因打车退款含平台兜底语义）；WITHDRAW→fund.withdraw（资金单）。确定性 bizNo/requestId 幂等 + 退避重试 + DEAD 人工重放；payment-pro 宕机时 ride 本地资金流不受影响（PENDING 留痕），恢复后镜像追平。支付手续费（fee-bps→SYS_FEE）为支付系统营收，ride 侧对账口径予以扣除。详见 ride-pro DESIGN.md §19。

### 16.4 验证记录（2026-11-17）

ride-pro `verify-integration.py` 15/15（充值/支付/部分退款/提现双账本增量一致、回调留痕、停 payment-pro 混沌本地不受影响+恢复追平、两边对账全过）；本系统回归 smoke-test.py 79/79、verify-pro.py 22/22，OpenAPI/HTTPS 扩展未破坏既有行为。

## 17. 资金单与全局分页流水（v1.7.0）

### 17.1 充值/提现升级为交易单+支付单

v1.3.0 的 account.topup/account.withdraw 仅产生账务分录，无交易单/支付单，与商户交易 PAY 不对称。v1.7.0 新增 `PaymentService.fundPay(FundPayRequest)`，使充值/提现与 PAY 同构——**每笔资金操作都有一笔交易单 + 一笔支付单**：

- **biz_type 判别**：trade_order 与 payment 各增 `biz_type VARCHAR(16) NOT NULL DEFAULT 'TRADE'`（schema.sql `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`），取值 TRADE/TOPUP/WITHDRAW。普通 `pay()` 显式置 TRADE，`fundPay()` 置 TOPUP/WITHDRAW；OrderDTO/PaymentDTO 透出 bizType，控制台交易单/支付单列表新增「类型」列（充值/提现/交易徽标）。
- **系统资金商户 SYS_FUND**：`ensureFundMerchant()`（synchronized）懒创建 name=SYS_FUND 的 MERCHANT 会员 + 账户，作为资金单商户。资金单 **fee=0、method=BALANCE、无渠道分支**。
- **完整 TCC**：`coordinator.begin("PAY")` + 账务分支（fundAccountingContext）+ 交易分支（tradeContext），tryAll→commitAsync。账务 bizNo=**支付单 id**、refId=**交易单 id**：TOPUP D SYS_CASH / C 用户；WITHDRAW D 用户 / C SYS_CASH。借贷净额与旧 account.topup/withdraw 完全一致。
- **幂等**：FundPayRequest.requestId（=ride refKey）→ 支付单 request_id 唯一 + 交易单 outTradeNo 唯一；重复请求 replay 返回原支付单（repeated=true）。WITHDRAW 先校验账户可用余额。
- **完结与对账自然齐备**：复用通用 `PaymentCompletionListener`——终态翻转 SUCCESS + 写 SETTLE_PAY/NOTIFY_PAY outbox。故资金单天然满足五方对账：① transfer(支付单 id) 借方合计==amount；② 交易单 PAID；③ 每笔支付单有 PAY 结算单；④ BALANCE 免渠道 CAPTURED。**ReconService 无需任何改动**。
- **OpenAPI**：`POST /openapi/fund.topup`、`/openapi/fund.withdraw`（验签后 fundPay），返回 PayResultDTO{payment:{id...},repeated}。ride-pro PaymentProClient.topup/withdraw 改调资金单端点并解析支付单 id 作回执；MirrorExecutor WITHDRAW 由 void 改为返回支付单 id。

### 17.2 账务流水跨账户全局分页查询

原 entries 查询仅限单账户、无分页。v1.7.0 新增全局分页：

- **契约**：`EntryQuery{accountId?,bizNo?,dir?,keyword?,page=1,size=20}`（getOffset=(max(page,1)-1)*size）；`PageDTO<T>{items,page,size,total}`。`AccountingFacade.entriesPage(EntryQuery)` → `Result<PageDTO<EntryDTO>>`。
- **实现**：EntryMapper 动态 `<where>` FILTER（accountId/bizNo/dir 精确 + keyword LIKE 匹配 subject/ref_id/account_id/biz_no）+ `selectPage`（ORDER BY seq DESC LIMIT #{size} OFFSET #{offset}）+ `countPage`。AccountingService.entriesPage 钳制 page≥1、size∈[1,200]。**accountId 留空即跨账户查总的**。
- **REST**：`GET /api/accounting/entries?accountId=&bizNo=&dir=&keyword=&page=1&size=20`。
- **控制台**：账务流水卡升级为全局分页——账户下拉含「全部账户」+ 借贷方向 + 关键字筛选 + 上一页/下一页分页器（共 N 条·第 x/y 页），9 列（分录号/时间/账户/借贷/金额/变动后余额/账务单据/参考号/摘要）。资金单分录账务单据=支付单 id、参考号=交易单 id、摘要=充值/提现。

### 17.3 验证记录（2026-11-17，v1.7.0）

本系统 smoke-test.py **79/79**、verify-pro.py **22/22**（含持久化重启）；ride-pro verify-integration.py **24/24**（[A]/[D] 校验资金单 TOPUP/WITHDRAW 交易单 PAID + 支付单 + 账务 refId 可见）、单测 **19/19**。控制台浏览器实测：全局流水 109 条/6 页翻页正常、借贷筛选（D 向 51 条）、账户「全部账户」跨账户查询、交易单/支付单「类型」列充值/提现/交易徽标、资金单退款按钮隐藏（仅 TRADE 可退）；ride 管理端流水「支付回执」列显示资金单支付单 id。jar 内嵌静态资源与源码一致。

联调修复：① 普通 pay() 未置 bizType → INSERT 显式传 NULL 撞 NOT NULL（DEFAULT 仅在省略列时生效），补 `payment.setBizType("TRADE")`；② 交易单列表退款按钮对非 TRADE 资金单隐藏（与支付单列表一致）；③ verify-integration [G] 对账改轮询等待结算 outbox 异步投递收敛（消除时序抖动）。

## 18. Docker 中间件集成（MySQL/RocketMQ/Redis/ZooKeeper）

### 18.1 双 profile：零依赖 local 与中间件 docker

同一套代码、同一套 mapper SQL，通过 Spring profile 切换基础设施载体，**业务逻辑与资金一致性机制完全不变**：

| 维度 | `local`（默认，零依赖） | `docker`（共享中间件） |
|---|---|---|
| 存储 | H2 文件库 `data/<domain>.mv.db` | MySQL 单实例多库 `pp_<domain>`（3306） |
| 注册中心 | 内嵌 ZooKeeper（2181/8092） | Docker ZooKeeper（2181，跳过内嵌 registry） |
| 消息投递 | outbox 3s 轮询 | RocketMQ `PP_OUTBOX` 主投递 + 轮询兜底（9876） |
| 风控限流 | 进程内存滑动窗口 | Redis Lua 滑动窗口（6379） |
| 商户缓存 | 直查 member facade | Redis 缓存 TTL 60s（6379） |
| 启动 | `./run.sh` | `./run.sh --docker` |

中间件复用 **ops-pro `claim-shared` Docker 栈**（`ops-pro/deploy/middleware-compose.yml`），与 ride-pro/claim-pro 等共享同一 MySQL/RocketMQ/Redis 实例，端口全错开可同跑。docker profile 由各域 `application-docker.yml` 装配，开关均 `@ConditionalOnProperty`，local 模式 Bean 不加载、连接不建立（Lettuce/MQ 客户端懒加载），故新增 redis/mq 依赖**不破坏 local 零依赖启动**。

### 18.2 MySQL 单实例多库（9 域）

- **多库**：`pp_member / pp_account / pp_accounting / pp_trade / pp_channel / pp_settle / pp_notify / pp_recon / pp_payment`，建库脚本 `ops-pro/deploy/payment-databases.sql`（`CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci`）。risk/cashier 无库。
- **方言同构**：mapper 注解 SQL 两端通用。序列取号统一 `SELECT next_value_for('seq_x')`：
  - H2：`CREATE ALIAS next_value_for FOR "...H2Sequence.next"` → `SELECT NEXT VALUE FOR seq_x`。
  - MySQL：`sequences(name PK, value)` 表 + 存储函数 `next_value_for`，`UPDATE sequences SET value=LAST_INSERT_ID(value+1)` 原子取号。
- **建表**：各域 `schema-mysql.sql`（`CREATE TABLE IF NOT EXISTS` + sequences + 函数），对空库一次性执行；docker profile `spring.sql.init.mode=never`（不每次启动重跑）。TCC 日志表（`dtx_transaction/dtx_branch/tcc_branch_log/idempotent_record`）随域同迁。
- **MySQL 坑位**：① 显式 `CHARSET=utf8mb4` 不带 COLLATE 时列默认 `utf8mb4_0900_ai_ci`，sequences 表与函数参数须显式 `COLLATE=utf8mb4_unicode_ci` 避免混 collation 报错；② 建函数需 `log_bin_trust_function_creators=1`（binlog 开启时）；③ `lines` 等保留字反引号。
- **连接**：`jdbc:mysql://.../pp_<domain>?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true`，口令来自仓库外 `docker.env`。

### 18.3 RocketMQ 主投递 + outbox 兜底

保留事务性 outbox 作为**唯一事实来源**，RocketMQ 只做加速主路径，轮询兜底永不删除（服务端可靠性兜底）：

```
onConfirmed（TCC 终态同事务）
  ├─ INSERT outbox(status=PENDING, next_retry_at = MQ模式 ? now+4000ms : 0)
  └─ afterCommit ──▶ RocketMqOutboxPublisher.publish(event,bizId,payload)
                       Message(PP_OUTBOX, tag=event, key=bizId, body={event,bizId,payload})
消费侧（payment-payment 内 OutboxMessageListener）
  └─ deliverByEventBizId(event,bizId) ──▶ 复用幂等 Dubbo 投递(settle/notify) + markDelivered
       异常 → RECONSUME_LATER（RocketMQ 重投）；成功 → CONSUME_SUCCESS
兜底（OutboxDispatcher 每 3s）
  └─ 仅扫 next_retry_at 到期的 PENDING（MQ 已送达的行已 DELIVERED，不会重复）
```

- **配置**（`payment.mq.*`）：`enabled`、`namesrv=127.0.0.1:9876`、`topic=PP_OUTBOX`、`producer-group=payment-outbox-producer`、`consumer-group=payment-outbox-consumer`、`fallback-grace-ms=4000`。
- **客户端**：原生 `rocketmq-client` 5.1.4（非 spring starter，对齐 claim-pro 已验证模式），`DefaultMQProducer/DefaultMQPushConsumer` `initMethod=start/destroyMethod=shutdown`，`@ConditionalOnProperty(payment.mq.enabled=true)`。客户端日志在 `~/logs/rocketmqlogs/`（非应用日志）。
- **幂等不变**：消费侧走与轮询完全相同的 `IdempotentTemplate` 路径（SETTLE_PAY+paymentId / SETTLE_REFUND+refundId / NOTIFY+event:bizId），MQ 重投/轮询补发均不产生重复结算或通知。
- **grace 设计**：MQ 模式 insert 即置 `next_retry_at=now+4000ms`，给 MQ 主路径 4s 窗口；3s 轮询只在该窗口后补 MQ 未送达的行，正常态零重复投递。

### 18.4 Redis 风控限流 + 网关商户缓存

- **风控滑动窗口**（`RedisRateLimiter`，`payment.risk.redis.enabled=true`）：Lua 原子脚本对 ZSET `pp:risk:window:<payer>` 做 `ZREMRANGEBYSCORE`(清窗口外) → `ZCARD`(计数) → 超限返回 0，否则 `ZADD`(member=now:UUID) + `PEXPIRE`(窗口长)。窗口 `WINDOW_MILLIS=10000`、`WINDOW_MAX=5`（第 6 笔拒绝），与 local 内存版语义一致。`InMemoryRateLimiter` 为 local 回退（`matchIfMissing=true`）。
- **网关商户缓存**（`MerchantCache`，`payment.cache.redis.enabled=true`）：OpenAPI 验签先 `GET pp:openapi:app:<appId>`（JSON→MemberDTO，TTL 60s），miss 回源 member facade 并 best-effort 回填；读异常降级直查。local 模式禁用，直查 facade。
- **序列化复用** `TccContextUtil.toJson/fromJson`（Jackson 传递依赖），不新增 ObjectMapper Bean。

### 18.5 Docker ZooKeeper（跳过内嵌 registry）

docker profile 不再启动 `payment-registry` 内嵌 ZK，改用共享栈 ZooKeeper `2181`。`run.sh --docker` 先 `/dev/tcp/127.0.0.1/2181` 探测（30s），不可达即报错提示先在 ops-pro 起 zookeeper；可达则 11 域 + gateway 直接注册到 Docker ZK。Dubbo 端口分配不变（gateway 20880、域 20881~20891）。

### 18.6 启停与只读数据迁移

```bash
# 中间件（ops-pro 共享栈）先起：MySQL/RocketMQ/Redis/ZooKeeper
./run.sh --docker            # 编译 + docker profile 启动（--no-build 跳过编译）
./stop.sh                    # 优雅停止（data/ 与 MySQL 数据均保留）

# 只读 H2 → MySQL 迁移（把本地历史数据搬进 pp_*，原 H2 库绝不修改）
./migrate-h2-to-mysql.sh --dry-run   # 只统计待迁移行数/序列
./migrate-h2-to-mysql.sh             # INSERT IGNORE 去重写入 + 序列回填
```

- **迁移工具**（`tools/MigrateH2ToMySQL.java`）：把 `data/<domain>.mv.db` **复制到临时目录**后以 `ACCESS_MODE_DATA=r;MODE=MySQL;DATABASE_TO_LOWER=TRUE` 只读打开（原库不被打开/修改）；逐表 `INSERT IGNORE`（按 PK/唯一键去重，可重复执行）；序列按 H2 `INFORMATION_SCHEMA.SEQUENCES.BASE_VALUE` 回填 MySQL `sequences.value`（`GREATEST` 取大，避免取号回退碰撞）。缺表告警跳过、按域事务提交。
- **密钥仓库外**：`~/.config/payment-pro/docker.env`（600，真实 MySQL 口令），仓库仅留 `config/docker.env.example`。
- **数据保护**：`data/` H2 库与 MySQL 数据的删除/重置须用户明确确认；迁移只读不删。

### 18.7 验证记录（2026-11-18，docker profile）

重置 pp_* 合成测试数据后全量回归：smoke-test.py **79/79**、verify-pro.py **22/22**（`PP_PROFILE=docker`，含 [D] 全停重启 MySQL 持久化恢复）。中间件实测：

- **RocketMQ**：`PP_OUTBOX` 四队列 broker/consumer 偏移完全一致，`Consume Diff Total=0`、`%RETRY%payment-outbox-consumer=0`（零积压零失败）；并行 ride-pro 流量亦经 MQ 干净消费。
- **Redis**：风控 ZSET `pp:risk:window:<payer>` 按时间戳评分、member 唯一、PTTL≈窗口长；网关缓存键 `pp:openapi:app:appM1` 命中；控制台支付单列表可见多条「交易过于频繁」限频拒绝（限流生效）。
- **MySQL**：控制台实时渲染会员/账户/交易单/支付单（99 行），账务试算平衡（借=贷），13/13 健康。
- **local 回退**：payment-risk 以 local profile 独立启动成功（`Started RiskApplication`，Redis 懒加载无连接、InMemoryRateLimiter 生效），证明新增依赖不破坏零依赖启动。

## 19. P0 生产化升级（v1.9.0）

> 依据 ROADMAP.md / SPEC.md / IMPLEMENTATION-PLAN.md（89 张任务卡：P0 23 / P1 47 / P2 19）。本章记录**已落地并实测验收**的 P0 能力；wave3（I-2 配置中心 / I-3 调度 / I-4 事务消息 / I-7 网关治理）与 I-5 分片进行中，落地后补入。

### 19.1 升级红线（不可破坏）

| 红线 | 约束 |
|---|---|
| ride-pro 双账本兼容 | 8 个 OpenAPI 方法冻结：`account.open`/`fund.topup`/`fund.withdraw`/`account.transfer`/`account.balance`/`trade.create`/`trade.pay`/`trade.query`，入参名/返回字段/幂等键/语义不变，新增字段仅可选 additive |
| BALANCE 同步 | 无渠道分支保持同步受理→PROCESSING→SUCCESS，**不得引入 WAITING/异步回执**；tcc WAITING 仅对 CARD 异步渠道 opt-in 启用 |
| HMAC 验签 | appId/sign/timestamp（**毫秒**）/TreeMap 排序 `k=v&` 机制不变；`/openapi/**` 不走 OAuth2/RBAC |
| 通知回调 | 继续支持 **http** callbackUrl，签名头只能 additive，body 结构不变（ride 不验签也能收） |
| 手续费口径 | feeBps→SYS_FEE 不变，动态配置默认值必须等于改造前现值 |
| 事务内核 | 保留自研 **tcc-core**（不迁 Seata）、保留 **ZooKeeper**（不迁 Nacos） |
| 可靠性底座 | **outbox 是唯一事实来源**，轮询兜底永不删除；服务端机制兜底一致性，前端补偿只管展示 |
| 数据保护 | `data/` 与 H2/MySQL 数据删除重置须用户明确确认；schema 只能 additive |
| 门禁 | 每波集成必跑 ride `verify-integration.py` **24/24** + payment `smoke-test.py` **79/79** 双绿才销项 |

### 19.2 账务防线：红冲蓝补 + 挂账科目 + 凭证防篡改（AC-1 / S-6）

- **红冲**：`AccountingService.reverse(ReverseRequest{origBizNo,reason,requestId})`，幂等键 `(REVERSE, origBizNo)`。冲正凭证 `bizNo="RV"+seq`、`ref_id=原 bizNo`、借贷腿 **D↔C 互换**，原凭证不可篡改；`TransferMapper.selectReverseByOrig`（`ref_id=orig AND biz_no LIKE 'RV%'`）派生关联，**无 schema 变更**。守卫 `origBizNo.matches("RV\d+")` 拒绝"冲正冲正单"。`TransferDTO.reverseBizNo` 回传。
- **挂账**：新增系统科目 `SYS_SUSPENSE`，`AccountingFacade.suspensePost/suspenseBalance`，幂等键 `(SUSPENSE, bizNo)`；`TrialDTO.suspenseBalance` 纳入试算。
- **凭证防篡改**：`VoucherGuard` 保证 `ledger_entry` append-only；`transfer_record.voucher_type` 区分 `NORMAL/REVERSE/SUSPENSE`（H2/MySQL 双份 additive）。
- 端点：`POST /api/accounting/transfers/{bizNo}/reverse`、`POST /api/accounting/suspense`、`GET /api/accounting/suspense/balance`。
- 实测：`verify-reverse.py` **16/16**、`verify-suspense.py` **28/28**。

### 19.3 差错处理平台（X-1 / S-4 / RC-1 / N-2）

- **表**（pp_recon，H2/MySQL 同构）：`dispute_order`（dispute_no UNIQUE、type、source、biz_no、payment_id、amount、status、strategy、assignee、fix_biz_no、check_name、biz_date…）、`dispute_fix_log`（每次动作留痕）。
- **状态机**：`OPEN→ASSIGNED→FIXING→RESOLVED→CLOSED`，`OPEN/ASSIGNED→IGNORED`（reject）；非法迁移拒绝，终态不可再改。
- **分类口径**（`DisputeClassifier`）：金额不一致 `AMOUNT_MISMATCH`→红冲+蓝补；单边账 `ONE_SIDED`/渠道缺失 `CHANNEL_MISSING`/手续费差 `FEE_MISMATCH`→挂账待人工；状态不一致 `STATUS_MISMATCH`→人工；通知死信 `NOTIFY_DEAD`→重放。
- **RC-1 接入**：六项对账失败按差异性质幂等建单 `(DISPUTE, source[:checkName][:bizDate]:bizNo)`；**对账判定口径与 `POST /api/recon/run` 返回结构不变**；复核通过自动 `RESOLVED` 并留痕 `AUTO_RESOLVE`。
- **S-4 自动修复**：**默认影子模式** `recon.dispute.autofix=false`（双 profile 一致），只给建议策略不动账；开启或人工 approve 才执行，修复凭证 bizNo 确定性生成（`DSPF/DSPB+disputeId`），幂等键 `(DISPUTE_FIX, disputeId)`，修复后校验试算平衡，失败回滚状态并告警。
- **N-2**：通知重试耗尽→ERROR 告警 + `payment_notify_dead_total` + 自动建 `NOTIFY_DEAD` 工单（幂等 by notifyId）+ `NotifyFacade.replay` 重放，送达后自动核销。
- **跨域接线**：S-7 备付金账实差异→`ReserveCheckService.raiseDispute`，`source=RESERVE`、`bizNo=核对流水号`、`strategy=MANUAL`（银行侧差异不臆断冲正）；差错平台不可达只降级告警，不阻断核对。
- 端点：`GET /api/disputes`（分页/过滤）、`GET /api/disputes/{id}`（含审计）、`GET /api/disputes/stats`、`POST /api/disputes/{id}/assign|approve|reject|resolve|close`、`POST /api/disputes/autofix/run`。
- 实测：`verify-dispute.py` **73/73**。
- **坑**：指标名 `dispute_created` 与 Prometheus `_created` 采集序列冲突致建单回滚 → 更名 `dispute_new` + 指标静默降级。

### 19.4 渠道异步回执与补单收敛（CH-1 / S-5 / P-1）

- **tcc-core 增量**：`beginAwaiting/awaitExternal/resumeCommit/resumeCancel`，仅 CAS 迁移，**向后兼容**（同步路径逐字节不变）。`PAYMENT_CHANNEL_ASYNC=true` 才启用异步，默认 false；BALANCE/无渠道分支永不 WAITING。
- **渠道域**：`ChannelQueryService.queryChannelTrade`（只读返回 CAS 状态）、`ChannelReceiptService.receiveReceipt`（终态回执落地 + `channel_receipt_log`）、`MockBankAsyncJob`（模拟银行异步落地）。
- **支付域**：`ChannelCompletionService`（回执驱动 resumeCommit/resumeCancel）、`PaymentReconcileJob/Service/Decision` + `ReconcilePolicy`（PROCESSING/WAITING 扫描→查询补单→收敛；查询次数耗尽转人工/差错）。
- 端点：`GET /api/channel/trades/{bizNo}`、`POST /api/channel/receipt`、`POST /api/payments/{id}/reconverge`。
- **资金护航**：WAITING 期间分录数 0、未过账、订单仍 PAYING；终态只翻一次；重复/中间态/金额不符回执一律吸收不动账。
- 实测：同步 `verify-reconverge.py` **26/26**；异步 + 混沌 **55 过/1 败/1 跳过**（该败为脚本现金户符号写反，已改为按本单分录核算现金腿 `-2000`，待复跑）。

### 19.5 通知签名（S-3 / N-1）

`NotifySigner`：HMAC-SHA256 over `body + "\n" + timestamp + "\n" + nonce`，密钥取商户 appSecret（`getByAppId` 内部链路）；有 RSA2 证书走 SHA256withRSA，缺失降级 HMAC。头 `X-Payment-Signature`/`X-Payment-Timestamp`/`X-Payment-Nonce`/`X-Sign-Type`（**additive**）。签名失败→PENDING + 告警标记 SIGN，不静默丢弃。https 推送信任 `config/payment-pro-cert.pem`，**http 回调继续支持**。实测 `verify-notify-sign.py` **20/20**。

### 19.6 控制台认证 / RBAC / 审计 / 密钥（S-2 / G-1 / M-1 / S-1）

- **认证**：Spring Security `SecurityConfig` + `BearerSessionFilter`，STATELESS；`SessionStore`（docker Redis / local 内存降级）。`POST /api/auth/login|logout|refresh`、`GET /api/auth/me`。初始 admin 由 `ConsoleAuthService.seed()` **幂等仅缺失时插入**，口令取 `PP_ADMIN_PASSWORD`（默认 `admin123`），BCrypt 落库。**改环境变量不会更新既有 admin 口令**。
- **开放面 permitAll**：`/openapi/**`（HMAC 独立链）、`/api/health`、`/actuator/**`、静态控制台。`/api/**` 未登录 **401 A0401**、越权 **403 A0403**，均写审计。
- **RBAC**：四内置角色 ADMIN/OPERATOR/AUDITOR/READONLY，ADMIN 持通配 `*`；端点→权限点映射（`dispute:approve`、`payment:refund`、`accounting:reverse`、`member:secret`、`audit:read`、`console:admin`…）。表 `console_user/console_role/console_user_role/role_permission/audit_log` 落 **pp_member**（网关无 datasource，经 `AuthFacade/ConsoleFacade` Dubbo 调用）。
- **审计**：`AuditFilter`（order -50，Security 链之后）记录 who/method/path/bizNo/结果/IP/traceId；`GET /api/audit` 分页（ADMIN/AUDITOR）。
- **登录限流**：`LoginRateLimiter` 同用户 5 次错口令→**429 A0429**，锁定期内正确口令也拒。
- **密钥轮换 M-1**：`member_secret_version`（version/secret_cipher/status ACTIVE·RETIRED/grace_until，UNIQUE(member_id,version)）。验签先 ACTIVE 再宽限期 RETIRED，**轮换不停机**；宽限期后即时拒绝（缓存 TTL 内也生效）。版本列表只返回指纹（SHA-256 前 16hex），**绝不返回明文**；明文仅创建/轮换响应一次性返回。
- **证书**：`member_cert`（SN/公钥/有效期），`CertService.scheduledScan` 到期扫描→ERROR 告警 + `payment_member_cert_alert_total` / `payment_member_cert_expiring` gauge。
- **S-1**：`SecretProvider` SPI + `LocalFileSecretProvider`（`~/.config/payment-pro/` 600）+ `VaultSecretProvider`（KV v2，配置缺失自动降级 local）+ KMS 可插拔。**真实 Vault 服务端未部署**，密钥仍在仓库外文件，满足红线。
- **E-1 脱敏不回退**：`MemberConverter.toDTO` 置 appSecret/certPublicKey 为 null，`toDTOWithSecret` 仅 create/getByAppId 使用。
- **缓存一致性**：`MerchantCache`（`pp:openapi:app:*` 60s TTL）在**轮换与证书上传成功后均 evict**（修复：uploadCert 原先漏 evict 致 RSA2 误报"证书缺失"）。
- 实测：`verify-rbac.py` **51/51**、`verify-secret.py` **32/32**。

### 19.7 资金防线（S-7）

- **大额双人复核**：`amount_review`（pp_payment，UNIQUE(biz_type,biz_no) + seq_review），`AmountReviewService` + `ReviewFacade`。阈值默认 **1000000 分（1 万元）**可配；未复核拒绝 **A0428 REVIEW_PENDING**，approve 后才走原 TCC；**同人提交+审批拒绝**；幂等 `(REVIEW, bizType:bizNo)`；重启复核单不丢、未复核分毫不动。
- **兼容取舍**：复核闸门落在**网关 `/api/**` 拦截器**而非 PaymentService，`/openapi/**` 资金单路径逐字节不变（§19.1 红线）。提现仅存在于 OpenAPI，故实际覆盖大额退款 + 冲正。
- **二次认证**：`StepUpController`（`POST /api/security/step-up`，口令再验）→ 300s 短时 token（`StepUpTokenStore` Redis/内存），`StepUpAuthInterceptor` 校验 `X-Step-Up-Token`，缺失 **403**。覆盖：复核 approve/reject、备付金 adjust、密钥轮换、大额退款/冲正。**会员/账户状态变更暂未纳入**（待脚本协同后收紧）。
- **备付金账实核对**：`reserve_check_log` + `reserve_bank_adjust`（pp_accounting）。账面 = SYS_CASH 账务余额；实际 = 账面 + 模拟银行独立事件（`reserveAdjust` 注入，**只影响实际侧，绝不触碰凭证与余额**）。差异→ERROR 告警 + `payment_accounting_reserve_diff_total` + 留痕 + RESERVE 差错单。P1 AC-5 将以真实渠道账单替换模拟口径。
- 端点：`GET/POST /api/reviews`、`POST /api/reviews/{id}/approve|reject`、`POST /api/accounting/reserve/check`、`GET .../logs`、`POST .../adjust`。
- 实测：`verify-fund-guard.py` **34/34** + 重启混沌段 **8/8**。

### 19.8 可观测（I-8）

13 域全部暴露 actuator（原 `web-application-type=none` 的 12 域改 servlet，内嵌 Tomcat 仅承载 actuator）。management 端口镜像 Dubbo 2088x → **18081-18092**（registry 18092），网关与业务 HTTP 同端口 8080。`MetricsSupport` + `MetricsAutoConfiguration`（common 自动装配）统一命名 `payment_<domain>_<event>_total`；`PaymentTccMetrics` 暴露 gauge **`payment_app_tcc_dead_backlog`**（DEAD 积压）。`show-details=always`。实测 `PP_PROFILE=docker verify-metrics.py` **96/96**。

### 19.9 验证矩阵与重启门禁

| 脚本 | 覆盖 | 结果 |
|---|---|---|
| smoke-test.py | 全链路 79 项 | 79/79 |
| verify-pro.py | 并发幂等/订单竞态/渠道宕机 TCC 补偿/全停重启持久化 | 22/22 |
| verify-reverse / suspense | 红冲 / 挂账 | 16 / 28 |
| verify-notify-sign | 通知签名验签/篡改/降级 | 20/20 |
| verify-dispute | 差错平台全生命周期 | 73/73 |
| verify-fund-guard | 复核/备付金/step-up + 重启混沌 | 34 + 8 |
| verify-rbac / secret | 认证鉴权审计 / 密钥轮换证书 | 51 / 32 |
| verify-metrics | 13 域指标（docker） | 96/96 |
| verify-reconverge | 补单收敛（同步 / 异步+混沌） | 26 / 55·1 败已修待复跑 |
| ride verify-integration | **跨系统兼容门禁** | **24/24** |

**重启门禁（血泪教训，已固化为流程）**：
1. `stop.sh` 加固：pid 等待/SIGKILL 之后**再清理未被 pid 跟踪的残留实例 + 等待网关端口真正释放**。否则垂死旧进程仍占 8080 应答（Dubbo directory 已销毁）返回 503，而 `run.sh` 就绪探测只看 HTTP 200 → **新栈假就绪**。此根因曾同时造成 verify-pro C 段阻塞与异步段 2 项失败。
2. **稳定性门禁**：连续 3 次 `/api/health` 13/13 + `POST /api/auth/login` 200 + 带 Bearer 的 `GET /api/members` **与 `GET /api/payments`** 均 200（多 facade 真实往返），才允许跑脚本。
3. **严禁单域重启**：provider 单独重启后 consumer invoker 可能丢失（`validInvokers:0` 不重连），一律全栈 `./stop.sh && ./run.sh --docker --no-build`。
4. **脚本自行重启子进程必须继承 `~/.config/payment-pro/docker.env`**（`docker_env()`）：run.sh 会 source，脚本不会；漏继承则 MySQL 空口令 → 进程启动即 `Access denied (using password: NO)` 死亡，表现为下游 `No provider available`。
5. 脚本取静态页/`/actuator/prometheus` 须用对应 `Accept`（`text/html`/`text/plain`），否则 Spring 内容协商返回 **406** 造成假失败。
6. ride `/api/health` 返回 **dict**（`{registry,services[]}`），payment 返回 **list**，探测脚本不可混用假设。

### 19.10 已知边界与待办

- wave3 进行中：I-2 Apollo（local 文件降级）、I-3 XXL-Job + ShedLock、I-4 RocketMQ 事务消息 + 多 topic、I-7 Sentinel + nonce 防重放（**nonce 必须可选**，ride 不传）。
- I-5a/I-5b 分片未做（XL，资金最高风险，P0 末做，对账护航 + 单库可回滚，E-7 幂等键跨片用基因法/独立索引表）。
- 异步段复跑、local 零依赖 profile 全量复验待补。
- 真实 Vault/KMS 服务端未部署；真实外部渠道适配属 P1 CH-2。
- 会员/账户状态变更纳入 step-up 待脚本协同后收紧。
- **C 端移动收银台尚未实现**：`ui-design/` 原型含 12 屏支付宝风格移动收银台（纯 mock），运行态只有 `/api/cashier/pay` API；页面落地属 P1 C-1/C-2/C-5。
- 管理控制台已补齐差错/复核/备付金/二次认证/配置中心五页并对齐支付宝蓝 `#1677ff`（静态资源打进 jar，**须重建网关 + 重启才生效**）。

## 20. P1 核心支付能力（v1.9.0，47 卡全验收）

> 十泳道并行落地，每道独立专项脚本 + 主会话集成验收。门禁：smoke **79/79** + verify-pro **22/22**（须在 **ride 静默窗口**跑，见 §20.11）+ ride `verify-integration.py` **24/24** + 六项对账 pass + 试算 D=C 且 Σbalance=0。

### 20.1 渠道与路由（CH-2a/2b/3/4/5）

- **ChannelAdapter SPI**：`pay/refund/query/close/parseReceipt/downloadBill/queryLimit`；四实现——MockBankAdapter（MOCK_BANK + MOCK_BANK_B 双实例）、SandboxBankAdapter、EchoChannelAdapter（出厂 DISABLED，证明可插拔）。**模拟银行永久保留为 local 降级与混沌靶**，默认同步路径文案/错误码/状态机/手续费口径逐字不变。
- **沙箱边界**：无真实银行凭据、不连外网；SandboxBankController 是本进程内模拟银行账本 `sandbox_order`，ChannelHttpClient 走 loopback 真实 HTTP + HMAC（SecretProvider ref `channel-sandbox_bank`，`env=SANDBOX` 隔离）。接生产同 SPI 换 base-url/密钥/报文即可。
- **路由**：硬过滤（停用/维护窗/单笔+日限额/失败率熔断/Sentinel/异步 opt-in）→ 加权评分 `成功率×100 − feeBps/100 − 耗时s + weight/100 + 主渠道偏好30`（`channel.route.primary-bonus`）。**切换仅在 `failoverAllowed=true`（确未受理）时沿候选序列；受理后超时一律 PROCESSING 交补单**。结构性护栏：`channel_trade.biz_no` PK 一单一行一渠道；拒绝/超限/无可用渠道均在落行前抛错 → TCC Cancel 空回滚，不产生悬挂单。
- **对账文件**：CSV（表头+明细+`#TOTAL` 声明汇总）落 `data/bills/<code>/<date>.csv`；幂等 `UNIQUE(code,biz_date)+(bill_id,channel_order_no)`，历史行标 `present=0` **不删行**；汇总不符/解析失败→FAILED+差错单；逐笔比对 `AMOUNT_DIFF/STATUS_DIFF/TYPE_DIFF/LOCAL_MISSING/BILL_MISSING` → `DisputeFacade.create(source=PAYMENT, checkName=CHANNEL_BILL, strategy=MANUAL)`，不自动动账。
- 实测 `verify-channel.py` **111/0/1 跳过**（G 异步段需 `PAYMENT_CHANNEL_ASYNC=true` 另跑）。

### 20.2 风控（R-1/2/3/4）

- **规则引擎**：QLExpress，DSL 例 `amount > 100000 && txnCount(payerId,86400000) > 10` → REJECT/score；函数 `txnCount/quotaUsed/quotaCount/inList/deviceRisk/ipRisk/hourOfDay/amountDeviation`。经 DynamicConfig `risk-rules` namespace 四键下发（Apollo 推送 + 5s 轮询，**不重启生效**）。
- **版本与灰度**：四类载荷统一版本快照 `risk_config_snapshot`（CONFIG/CONSOLE/ROLLBACK + operator + note 审计）；灰度 FNV 哈希 %100 + 白名单恒生效；一键回滚取上一版生成 ROLLBACK 新版，**配置未变故轮询不覆盖**。
- **误配置护栏（红线）**：`QLCompileException`/`QLBizException`(如 1/0)/`QLTimeoutException`(限定 50ms) **一律跳过放行并告警**（<3s 返回），同批正常规则仍拦截、内置单笔限额仍拒；JSON 不可解析→last-known-good。告警落 `risk_alert` + Micrometer。
- **多维限额**：单笔/日/月 × USER/MERCHANT/CHANNEL/METHOD/CARD(BIN)，组合（如 `USER,METHOD`）独立计数、scopeKey 限定、周期 key=yyyyMMdd 自然重置；**8 线程恰好 3 笔放行不超扣、拒绝不消耗额度**。默认值口径未变：single-limit=10000000、window-millis=10000、window-max=5、fee-bps=60。
- **名单**：黑/白/灰（用户/商户/卡号/IP/设备），处置可配，变更审计，**白 > 黑 > 灰**。
- **评分分级**：FREQ25/AMOUNT_DEV20/LIST30/DEVICE25/IP20/TIME10 + 规则分；`<40 PASS / 40-59 CHALLENGE / 60-79 LIMIT(降额1000元,超则拒) / 80-94 REJECT / ≥95 MANUAL_REVIEW` → `DisputeFacade(source=PAYMENT, type=RISK_MANUAL_REVIEW, strategy=MANUAL 不动账)`，人审 approve→RESOLVED / reject→IGNORED，重复裁决 409。
- 实测 `verify-risk.py` **116/116**。**CHALLENGE 续付**已由 P-6/F 衔接（非 PASS 一律受理前拒绝 → CHALLENGE 验证后续付）。

### 20.3 会员 / 账户 / 进件（M-2/3/4/5、X-2、A-1/2/3）

- **KYC**：沙箱四要素 + SHA-256 哈希脱敏 + 幂等 + 开户闸门可配（默认关）。**分级限额** I/II/III 规则 + DynamicConfig 热覆盖 + 升级需 KYC + `checkLimit`（默认不误拦）。
- **进件审核流**：提交→通过/驳回/补件→重提，**CAS 防双审** + 留痕 + 执照幂等；权限点 `merchant:review`。
- **C 端登录（M-5，区别于控制台 S-2）**：口令/短信沙箱、5 次错口令锁 15 分、会话 local 内存 / docker Redis、改密验旧 + 全端失效、冻结拒登。端点 `/api/member/register|sms-code|login|logout|session|password` **permitAll**（会员会话与旧口令校验在 MemberAuthController 内）。
- **多账户类型（A-1）**：5 类型幂等开户，唯一键迁至 `(member_id, account_type)`，独立核算；`list` BALANCE 恒首、`getByMember` 缺省 BALANCE。
- **绑卡（A-2）**：四要素/Luhn/BIN 校验、默认卡、解绑顺位、重绑幂等；**卡号仅后四位**。
- **冻结细分（A-3）**：四原因金额冻结**经账务 TCC Try/Cancel**、解冻须对应原因、**司法双人**、状态 FULL/JUDICIAL 留痕。方向性 `CREDIT_ONLY/DEBIT_ONLY` **未假实现**，待账务闸门接线。
- **脱敏**：证件/卡/手机/执照 SHA-256 + 掩码，口令 BCrypt，验证码哈希，**明文零落库零日志**。新表 pp_member 7 张、pp_account 4 张 + account 增 5 列。
- 实测 `verify-member-account.py` **109/109**；`account.open` 幂等懒开户、`account.balance` 裸 long、E-1 list/get 全 null 均有断言。

### 20.4 清结算（ST-1..5）

- **T+N**：日切 + 批次状态机 `PENDING→SETTLING→SETTLED/FAILED`，JobHandler + **逐片 ShedLock**；`cycleDays` 默认 1 可配。
- **费率**：三层（商户/支付方式/交易类型）+ 阶梯 + 分成，经 `settle-fee` namespace 热更；**默认 feeBps=60、withdrawFeeBps=0**；无规则时 `calc source=DEFAULT`（3000 收 18 分）；热更 100bps 后 DB 规则仍优先(80)，**历史结算单 fee/批次 net 不变**（受理时快照）。
- **分账（结算侧）**：登记 + 复式记账 + **守恒护栏 Σ分账=结算净额（实测 9940，diff=0）** + 回退；重复消息同 id 不重复动账；新收款方超额拒绝且余额 0。**与 P-3 边界**：本域只「收结果→守恒校验→登记明细→复式记账划转」，**不建 share_order、不定比例**；分账明细**不写入 settle_record**，保 P0 summary 恒等式与 ride 口径。
- **提现到银行**：状态机 + 沙箱出款（BankPayoutAdapter/SandboxBankPayoutAdapter）+ 失败全额解冻 + 补单；SUCCESS 记 SYS_CASH 贷方（与 `fund.withdraw` 同口径）、同 requestId 不重扣、终态后乱序回执吸收、无 step-up token 403 且不动账。
- **账单**：日/月账单 + CSV 导出 + **跨片归并**（settle_record 顶层 SUM）。
- **9 张新表全部单库不分片**（状态机含 UPDATE 而影子写只 INSERT，分片会破坏回滚一致性；`withdraw_order.request_id`、`settle_share_detail(share_id,receiver)` 是全局幂等键）；**settle_record 零 ALTER**，批次归属存 settle_batch_item。
- 实测 `verify-settle.py` **158/158**。

### 20.5 账务（AC-2..5）

- **科目体系**：资产/负债/共同/损益类，科目号+名称+方向+层级+是否系统户。映射：`SYS_CASH→1001 资产D｜SYS_FEE→6001 收入C｜SYS_FUND→2005 负债C｜SYS_SUSPENSE→2003 负债C｜SYS_PAYOUT→2004 负债C｜SYS_CLEARING→3001 共同D｜SYS_PROFIT→4001 权益C｜客户账户默认 2001`。**映射只写标签列，余额=贷−借、feeBps→SYS_FEE、幂等键全不变**。
- **日切 + 日终批**：AccountingDayClock/AccountingDayService/DayEndBatchService，JobHandler `accountingDayCut`/`accountingDayEnd` + ShedLock（日切全局锁、日终逐片锁 + 科目取模）；`accounting_batch_run UNIQUE(type,date,shard)` 幂等；**默认不自动跑批**（auto-enabled=false，由 XXL/控制台/ppjob 触发）。第二进程并发 8 次触发 lockSkipped≥1 且只一条日切留痕。**损益结转默认关闭以保护 SYS_FEE 手续费闭环口径**，真实结转（D 收入/C SYS_PROFIT、归零、重跑不产生第二张凭证）在隔离 H2 内存 JVM 验证通过。
- **账簿/凭证查询**：总账/明细账/科目余额表/凭证联查（bizNo/refId/科目/日期区间），跨片分页与聚合归并正确，**凭证只读（VoucherGuard）**。
- **账实核对**：ReserveReconService 完善 S-7 雏形，差异分级告警 + 建差错单。
- 8 张新表全不分片；additive 列 `ledger_entry(+subject_code,biz_date，含 ledger_entry_0..3 物理表)`、`transfer_record(+biz_date)`、`reserve_check_log(+5)`、`reserve_bank_adjust(+channel_code)`。**零 DROP/DELETE/UPDATE 既有行**。
- 实测 `verify-accounting.py` **255/255**，影子比对 diffTotal=0。

### 20.6 通知 / 网关 / 多实例（N-3/4、G-2/3、I-9）

- **多事件类型**：新增 `SHARE_SUCCESS/SHARE_BACK/SETTLE_BATCH/SIGN_SUCCESS/SIGN_CANCEL/DISPUTE_OPENED`（envelope `{event,bizId,timestamp,payload}` + 模板必填校验）；**PAY_SUCCESS/REFUND_SUCCESS 标 legacy，body=payload 原样**（真实 trade.pay 回调 body 键集恒为 `{paymentId,orderId,amount,status}`，逐字节比对通过）；6 新事件商户 HMAC 验签全通过；`event:bizId` 幂等回调恰好 1 次。
- **查询/重发**：按商户/事件/状态/时间分页 + 单条/批量重发（幂等不重复推送已送达）+ **需 step-up** + 查询脱敏。
- **Swagger**：springdoc 2.3.0，`/openapi/**` 与 `/api/**` 分组，文档端点免鉴权且不泄敏感示例值，local 零依赖可访问。
- **统一错误码**：错误体 `{error,code,traceId}`；既有 `00000/A0400/A0401/A0403/A0404/A0409/A0429/B0001/B0002/B0003/C0001` 码值+文案+HTTP 映射**逐项断言不变**；新增 `A0428(400)/A0405(405)/A0413(413)/B0004(403)/C0002(504)` 仅 additive；**成功仍裸数据**（balance 裸 long、health/members 裸 list）。
- **多实例无状态（docker 实测）**：gateway-2@8081 会话互认、实例2 登出→实例1 立即 401、5 次错口令第 6 次 429 且实例2 同步 429、`pp:openapi:app:*` Redis EXISTS=1、nonce 跨实例 409、step-up token 跨实例可用；notify-2@18190 六条通知各恰好送达 1 次、shedlock notifyPush 恒单持有者。**临时实例与 pid 文件已清理**。local 为单实例语义（H2 文件库独占），Redis 故障时会话/限流/灰度降级本进程内存并告警。
- **灰度**：header 强制 / weight100 全量 / `crc32(token)%100` 分桶一致 / 共享 Redis 状态 / 一键回滚即时生效且 gray header 失效 / 目标宕机 **fail-open 200**。
- 实测 `verify-notify-gw.py` **149/149**。

### 20.7 交易产品（T-1..4）

多交易产品路由（即时到账/担保/预授权，默认产品行为不变）；**担保交易**买家付款→平台担保户→确认收货→打款卖家，超时自动确认经 JobHandler+ShedLock，每步复式记账+幂等+试算平衡；**预授权**冻结→完成/撤销→解冻，复用 account A-3 冻结能力，完成金额可小于冻结额（差额解冻）；**超时关闭**未支付订单经 JobHandler+ShedLock 不重不漏，**关闭须释放订单占用且防悬挂**（在途支付单不被误关，关闭与受理竞态有 CAS/状态守卫）。实测 `verify-trade.py` **131/131**。

### 20.8 对账（RC-2..5）与覆盖口径审计

- 日切批量对账（按**账面会计日**取数，共享栈会计日因多次手动日切已推进至 2026-11-26）、渠道内外逐笔比对、对账/差错时效报表 + CSV、分片调度不重不漏（并集=全量、交集=空，全局聚合项只由分片 0 核对）。
- **检查项数保持 6**：`verify-sharding/verify-job/verify-sharding-fund/verify-mq` 四脚本硬断言 `len(checks)==6`，故新覆盖**折进既有项而非新增**。
- **本轮补的两类盲区（原只遍历 SUCCESS 单）**：① 「支付-账务一致」增 **FAILED 单却有账务凭证**（已动账未冲正）；② 「支付-交易一致」增 **订单 PAID/PARTIAL_REFUNDED/REFUNDED 且 payment_id 指向 FAILED 单**。两类违规**建差错单(strategy=MANUAL) + ERROR 日志 + 指标 `recon_semantic_violation`，但不翻聚合 pass**——历史脏单修正方向属资金判定须人工/用户拍板，不能由对账自动动账。
- **COMBO 逐腿归集**：组合支付账务凭证按腿落账（`bizNo=legId`），主单无直接凭证；「支付-账务一致」对 COMBO 取 `getPayment(id).getLegs()` 逐腿 `transfer(legId)` 归集借方与主单金额比对，腿凭证缺失仍判单边账；**非 COMBO 判定口径逐字节不变**。
- **教训（写入流程）**：**对账全绿 ≠ 资金语义正确**，覆盖口径本身要被审计；断言资金缺陷前必须查全凭证派生形态（组合分腿 / 红冲 RV / 挂账 SUSPENSE），不能只按主单号查。
- 实测 `verify-recon.py` **83/83**。

### 20.9 支付编排（P-6、P-2..5）

- **支付路由**：按渠道成功率/限额/维护窗/成本择优（调 channel facade），路由失败不产生悬挂单；与 CH-3 边界——复用渠道路由，只做支付方式级编排。
- **组合支付 COMBO**：多腿独立 TCC 分支 + **整体原子**（任一腿失败全回滚）+ Σ腿=订单额；**按腿落账 bizNo=legId**。实证 P1256：PL5 BALANCE 6000（A863 D 6000 / A862 C 5964 / SYS_FEE C 36）+ PL6 CARD 4000（SYS_CASH D 4000 / A862 C 3976 / SYS_FEE C 24），**Σ腿借方 10000 = 主单金额，腿手续费 36+24=60 = 主单 fee**。
- **分账编排**：比例/固定额计算 + share_order + PP_SHARE 投递重试，调 settle `SettleShareService` 登记动账；Σ分账=净额误差 0、失败可回退幂等。
- **退款异步化**：受理→异步执行→终态回执/补单收敛；**outbox 仍唯一事实来源 + 3s 轮询兜底未删**；**`account.transfer/post` 同步语义逐字节不变**（ride 退款走 transfer 不走 trade.refund）；部分/多次退款与 refunded_amount/refunded_fee 口径不变。
- **关闭/撤销**：撤销当日未清算可撤、已清算走退款；**关闭/撤销与受理竞态 CAS 守卫防悬挂**。实证 P1455 经 P-5 关闭→CLOSED（failReason=支付关闭(P-5)）、订单 O2779→CREATED 且 paymentId=null，状态一致。
- 计费改接 settle `FeeCalculator`（默认 60bps 一致 + 受理快照不变）；风控 CHALLENGE 续付与收银台 C-3 衔接。
- 实测 `verify-payment-orchestration.py` **107/107**。

### 20.10 收银台与 C 端 H5（C-1/2/3）

- **多支付方式渲染**：`GET /api/cashier/methods` 按订单/商户/用户动态渲染 + 可用性/限额/维护窗提示，金额分→元。
- **付款码/扫码（沙箱）**：`USER_PRESENTED`（用户出示）/`MERCHANT_PRESENTED`（用户扫商户），生成→校验→一次性核销→自然过期 EXPIRED（默认 60s）→过期核销 409→防重放；不接真实微信/支付宝。
- **支付验证**：PASSWORD/SMS/BIO/SMALL_AMOUNT_FREE，连续错口令→LOCKED（锁定期正确口令也拦），`pay-verified` 与受理幂等衔接，验证失败不受理。
- **C 端 H5 落地（把 ui-design 纯 mock 原型变成真实页面）**：8 页 `/h5/{login,cashier,verify,paycode,scan,result,bill,wallet}.html` + 共享 `app.js/app.css/design-system.css`（**29.4KB 设计令牌原样复用，主色 #1677FF**）。零构建链、零外部 CDN；`api()` 封装**相对路径 `/api/*` + Bearer + `cache:"no-store"`**，401 清 token 跳登录带 `next` 回跳；**file:// 协议守卫在 app.js 第 8 行统一覆盖 8 页**（提示正确入口 `http://127.0.0.1:8080/h5/login.html`）。JS 语法经 Electron 内置 node `--check` 通过（本机 PATH 无 node）。
- 实测 `verify-cashier.py` **106/106**。**测试隔离要点**：红线段须用独立付款人/商户（复用同一账户付近百笔会触风控滑动窗口，风控在 TCC Try 内拒绝→FAILED，污染断言）；脚本对受理前 400 B0001 限频做**等窗口重置后重试**，不放宽阈值。

### 20.11 P1 验证矩阵与新增流程护栏

| 专项 | 结果 | 专项 | 结果 |
|---|---|---|---|
| verify-channel | 111/0/1跳过 | verify-accounting | 255/255 |
| verify-risk | 116/116 | verify-notify-gw | 149/149 |
| verify-member-account | 109/109 | verify-trade | 131/131 |
| verify-settle | 158/158 | verify-recon | 83/83 |
| verify-payment-orchestration | 107/107 | verify-cashier | 106/106 |

**新增/固化的流程护栏（血泪教训）**：
1. **ride 静默窗口**：pp 的 smoke/verify-pro 等含**全局增量断言**（结算笔数、商户余额增量、SYS_ 户余额）的套件，须在 ride-pro 停止时跑；否则 ride 并发镜像资金流污染断言（实测 ride 在线时 smoke 77/79，静默窗口 79/79）。顺序：停 ride → 跑 pp 套件 → 启 ride → 跑 ride 24/24 门禁。
2. **域间边预热**：稳定性门禁除 gateway 多 facade 往返外，还须预热 `account→member/accounting`（Dubbo 懒重连 `invokersToReconnect:1` 约 30s 自愈），否则重启后立刻跑脚本撞 `validInvokers:0` 假故障（POST /api/accounts 500 B0002）。
3. **分片表自动 additive 补列**：`ShardTableBootstrap.alignColumns` 启动时双向补列（只 ALTER ADD，统一可空 + 沿用源默认值，跳过自增列）。根治「业务域加列只改主表 → 分片表缺列 → 复制行报 Unknown column → dataSource 建不起来 → 域启动失败」（实测一次补齐 payment 16 列 / refund_record 20 列）。
4. **禁改他域 facade 接口**：只调既有方法；新增接口方法会致该域 impl 未实现而**全 reactor 编译失败**（本轮踩多次）。跨域新能力由主会话接线。
5. **锁协议**：同一锁名 `run/.wave3-lock`，构建/重启/验证/编辑共享文件必须持锁；**不得在他方持锁期间重启全栈或切换分片态**（曾致在途 TCC 被打断、smoke/recon 假失败）。代理轮次耗尽后锁成孤儿，主会话核产物后接管。
6. **代理 150 轮上限是常态**：窄范围派单（1-2 卡/泳道）、验证只跑本卡专项 + smoke，全套回归留主会话集成期；耗尽后**核产物完整性再 takeover**，不盲目重派。
7. **口令不进 argv**：诊断脚本用 defaults-extra-file 或 stdin 传 SQL，避免超时 traceback 把 MYSQL_PWD 回显进日志。

### 19.11 已拍板决策与生产部署必改项（2026-11-20 用户批准）

| # | 事项 | 决策 | 理由 / 处置 |
|---|---|---|---|
| ① | 历史脏单 **P360**（dtx=CONFIRMED、账务三腿齐全、订单 O303=PAID，但支付单 FAILED） | **依权威 dtx 修正为 SUCCESS**（不红冲退款） | 三者一致指向"支付其实成功"；红冲会造成资金退回但订单仍 PAID 的新不一致。**须经新增受控修复端点**（payment 域 additive：前置校验 dtx=CONFIRMED + 分录齐全 + 只允许 FAILED→SUCCESS，强制 step-up + 大额双人复核 + 审计），**不得直接 UPDATE 库**。根因（TCC confirm/cancel 竞态）已用互斥 CAS 修死 |
| ② | **I-5b 资金域分片切流** | **保持单库观察态，不切流** | 双写开、mirrorFailures=0、initial-active=false；afterCommit 双写存在失败窗口，虽有启动同步/resync/比对三重护航，观察期后再切更稳。切流与回滚各只需一条 `POST /actuator/ppshard {"action":"toggle","enabled":true/false}` |
| ③ | **损益结转** | **保持默认关闭** | 结转会归零 SYS_FEE 转入 SYS_PROFIT，破坏「SYS_FEE 余额 vs 结算净手续费」闭环口径（smoke/对账多处断言依赖）。真实结转已在隔离 H2 JVM 验证正确（D 收入/C SYS_PROFIT、归零、重跑不产生第二张凭证），生产需要时经配置开启并**按币种分组** |
| ④ | **会计日已推进至 2026-11-26**（真实日期 11-20） | **不回正，保留并记录** | 它是多次手动日切的真实结果，accounting_day/批次/日切留痕均按该日期生成且幂等键含 date；回正指针会造成会计日与既有留痕/批次归属不一致。**后续一律按账面会计日取数**，生产部署从新库起算 |
| ⑤ | **ride appM1 计费/配额** | **保持 legacy 免费、不限流、不限配额** | ride 是内部双账本镜像方而非外部商户，计费会引入无意义配额风险。未注册 appId 走 legacy 已是现状 |
| ⑥ | **cashier 沙箱开关** | **运行默认保持可用（联调需要）；生产部署必须置 `gateway.cashier-sandbox-enabled=false`** | 置 false 后 smsCode 不再返明文，ride `verify-cashier-ride` 56 项与 SDK 沙箱示例依赖它，故不改运行默认。**⚠ 生产必改项** |
| ⑦ | **CURTEST0d65f2be 残留测试户 100 万分** | **已清理（走 AC-1 红冲 + 双人复核 + 审计）** | 换汇三笔已被泳道正确红冲（USD 户归 0），唯一残留是最初充值 `CURTEST0d65f2beTP`。执行：首次红冲→400 A0428 建复核单 AR26→第二审批人(OPERATOR)批准(submitter=admin/approver=cleanupbob*)→红冲成功 **RV7883**(SYS_CASH C 1000000 / A1179 D 1000000)→**A1179 余额 0**、对账 pass、D=C=1214145380。原凭证保留，未直接改库 |
| ⑧ | **提交推送** | **在「全量构建绿 + P2 全验收 + 终版文档刷新」检查点提交并推送** | 不在半成品状态提交（曾有泳道在途编译错误）；payment-pro 与 ride-pro 各自只 stage 本会话文件，勿卷入并行会话改动 |

**生产部署必改项清单（上线前逐条核对）**：
1. `gateway.cashier-sandbox-enabled=false`（短信码不再返明文）
2. `PP_ADMIN_PASSWORD` 设为强口令（**注意种子幂等仅缺失时插入，改环境变量不会更新既有 admin 口令**，须走改密流程或新建管理员）
3. `payment.sharding.enabled` 按观察期结论决定；切流前跑全量对账 + 影子比对 diff=0
4. 损益结转如需开启，先按币种分组并核对 SYS_FEE 闭环口径断言
5. 会计日从新库起算（勿沿用测试推进的 2026-11-26）
6. Apollo/Vault/KMS 接真实服务端（当前 Vault 客户端已实现但服务端未部署，密钥在 `~/.config/payment-pro/` 600）
7. 渠道适配器换真实 base-url/密钥/报文（当前 MockBank/Sandbox 均无真实银行凭据、不连外网）
8. XXL-Job accessToken 与各执行器端口（9994-9998 已用满）核对；claim-pro 侧 token 需自行对齐
9. Sentinel 规则按真实容量调阈值（当前保守默认，拒绝在受理前）
10. `PP_MARKETING_ENABLED` 按需开启（关闭时营销抵扣编排完全短路、零 RPC、fee 不变）

## 21. P2 增值生态（v2.0.0，17/19 验收）

### 21.1 交易增值（T 系列）
| 卡 | 能力 | 关键设计 |
|---|---|---|
| T-1 | 担保确认 | `escrow_confirm_log`（order_id UNIQUE 全局幂等）、系统担保户登记 `trade_system_account`（name PK 即跨实例占位闸门） |
| T-2 | 预授权 | `preauth_record`（order_id UNIQUE）+ `preauth_complete_log`（request_id/biz_no UNIQUE）；冻结→完成扣款/撤销 |
| T-3 | 交易产品 | 产品目录 + 状态机，参数由 DynamicConfig（Apollo）热覆盖；`product_type` 存量由 DB 默认值回填 INSTANT |
| T-4 | 超时关闭 | `order_expire_log`（order_id UNIQUE ⇒ 不重不漏审计）、重开留痕 |
| T-5 | **合并支付 + 分阶段** | 见 §21.2 |
| T-6 | **撤销 vs 退款 + 改价** | 见 §21.3 |

### 21.2 T-5 合并支付与分阶段
**表**（全部单库不分片）：`merge_order`（request_id UNIQUE）、`merge_order_rel`（PK merge_id+order_id）、`order_stage_plan`（PK order_id+stage_seq）、`stage_pay_log`。

**合并支付**：承载单（carrier）+ 各子订单独立 `MERGE_PAY` TCC 分支 → 一子订单 Try 失败即整体回滚。
守恒：`Σ子订单额 = 合并额`（误差 0）、fee 按子订单分摊守恒、`Σ腿 = 合并额 = Σ子订单额`（COMBO 叠加时两层守恒）。
状态：`CREATED → PAYING → PAID`；失败出口一律重开回 CREATED（见 §21.10 缺陷 4/7/8）。

**分阶段**：`stage_count` / `paid_stage` / `paid_amount` / `stageAmounts`。
顺序期 CAS：`reserveStage(orderId, paymentId, expectPaidStage=seq-1)` 必须恰 1 行。
守恒：`Σ期额 = 订单额`、`paid_amount` 累加守恒、期撤销回退 `paidStage/paidAmount`。

**`stage_pay_log` 唯一键设计（v2.0 更正）**：
- `uk_stage_payment(payment_id)`、`uk_stage_request(request_id)` 保留
- 原 `uk_stage_order_seq(order_id, stage_seq)` **降为普通索引** `idx_stage_order_seq`
- 原因：期级唯一键挡住「当日撤销后重付同一期」的合法 INSERT；而每次 attempt 必须**独立留痕行**（`releaseStageAfterCancel` 按旧 paymentId 幂等重入，原设计注释明写「后续期重付不影响本留痕终态」）
- 双付守卫不削弱：订单级 `reserveStage` CAS + `uk_stage_payment` + `uk_stage_request` 三重
- 迁移：`TradeSchemaUpgrade.relaxStageOrderSeqIndex`，幂等、双 profile 同构、**只动索引零行改**；三处 schema 同步（`schema.sql` / `schema-mysql.sql` / `TradeSchemaUpgrade`）

### 21.3 T-6 撤销 vs 退款边界与改价
**判定边界**：
| 条件 | 走 | 凭证 |
|---|---|---|
| 当日未清算 + 全额未退 + BALANCE | **撤销** | `CX:paymentId` 全额反向，**无退款单**，`closeType=CANCEL` |
| 已清算 / 已部分退 / CARD | **退款** | 退款单 + 异步收敛 |
| 大额撤销 | 先双人复核 | `A0428 + reviewId`，绝不动账 |
| 非最新已付期 | 拒绝 | 409 |

**改价**：
- 未支付：`changePriceCas` 仅 `CREATED` 且无预留/无合并/无阶段计划；`original_amount` **首次留痕**、`price_change_count` 累加；不破坏 `merchant+outTradeNo` 幂等（同键返回原单新额）；fee 按新额快照
- 已支付：拒绝直接改价，引导**差额补退**（下调退差额 / 上调补收），`order_price_log` 留痕（request_id UNIQUE）
- **MySQL 陷阱**：`UPDATE` 赋值从左到右求值，必须 `SET original_amount=COALESCE(original_amount, amount), amount=#{newAmount}`，顺序颠倒会让留痕取到新价

### 21.4 资金增值（ST-6 保证金/垫资）
保证金缴存/释放/扣划 + 垫资垫付/追偿，**全部经既有 `accountingFacade.post`**（bizNo `DP:/DR:/DD:/AD:/AR:` 确定性派生 + IdempotentTemplate），**未自建账本**。
守恒：`balance(SYS_DEPOSIT) = Σ缴存−Σ释放−Σ扣划`；`used = Σ垫资−Σ追偿 = −balance(SYS_ADVANCE)`，diff 恒 0。
额度并发 5×1000 剩余 2000 → **恰 2 笔成功**。
`deposit_record`/`credit_account`/`advance_record` 三表 request_id 全局 UK，单库不分片，`settle_record` 零 ALTER。
SYS_DEPOSIT 复用既有 2006 科目；SYS_ADVANCE 暂落 2999 待映射（申请新增 1002 垫资应收款）。

### 21.5 开放平台增值（G 系列）
应用与授权（appId/scope/配额/计费，appSecret 仅注册响应返回一次）、沙箱（网关截流，只走 `openapi_sandbox_*` 账本，**生产资金一行不动**，生产必关）、配额与计费（计费账本独立于资金账本，超限受理前拒绝 A0429 不落单）、版本与 SDK（v1 冻结面逐字节不变 + 双 SDK 防漂移）、API 文档（springdoc 免鉴权生成期脱敏 + 仓库契约原文）。
`appM1` 免费不限为已拍板联调口径。

### 21.6 签约代扣（CH-4）
`channel.sign` / `channel.agent.pay`；在途单回填支付单号/说明（异步受理或状态未知时留痕，交补单收敛，**绝不臆断失败**）。
待拍板：`channel.sign` 的 merchantId 绑定校验（建议加）。

### 21.7 分片与影子双跑（I-5a/I-5b）
见 ARCHITECTURE §4.2/§4.3。`verify-sharding 123`、`verify-sharding-fund 253`；679 存量单快照逐条一致；`ppshard` toggle 双向回滚逐条一致（含序），差异非 0 拒绝切换；`shard_route_state` 持久化防重启方向反转。**当前保持单库观察态**，切流待拍板。

### 21.8 管理台（U 系列，v2.0 重点）
**12 业务分组两级导航**（`PP_IA`，69 个二级项），hash 路由、权限隐藏、rail 折叠态存 `localStorage(pp.console.nav)`。
**22 个原待建页全部建成真实页面**：trade-products / cashier-ops / combo-pay / kyc / onboarding / contract / bindcard / member-tier / member-auth / notify-resend / notify-tpl / open-apps / open-sandbox / open-quota / open-version / open-docs / canary / errcode / sentinel / jobs / shadow-diff / observability。
每页：真实数据列表 + 筛选 + 分页 + 详情抽屉 + 空/加载/错误态；单据与凭证/流水关联跳转；敏感写操作走 `guardedApi`（二次认证）。
**浏览器逐页实测 22/22 通过**（最多一页 14 表 124 行），零占位文案。

**实现要点（踩坑固化）**：
- 内容区是 `section#tab-<tab>`，**不是** `[data-tab]`（后者是导航项）
- `PPNav.route(false)` **只定位不取数**，取数由 refresh/点刷新触发
- 模块必须显式 `window.PPXxx = PPXxx`，否则 `if(window.PPXxx)` 恒 false、init 不执行 → **shim 单测全绿但真实页面白屏**
- `PPNav.ensureSections` 会给 index.html 缺失的 section 自动建**警告占位容器**，loader 自行覆写；故 13 个页面无需 index.html 预置 section
- 平台 `X-Frame-Options: DENY`，隐藏 iframe 验证路线不可用
- 表格空的排查顺序：会话过期(401) → 未点刷新 → 表头+空态行 → 慢端点（`/api/errors` 实测 10s）

**两处 404 属真实语义非故障**：
- `payment.gateway.sentinel_block`：指标名正确，计数器**首次拒绝后才注册**，未发生拒绝时 404 属预期（前端走 Prometheus 兜底）
- `/api/platform/jobs?service=member`：`JobAutoConfiguration` 是 `@ConditionalOnBean(JobHandler.class)`，member 域只有裸 `@Scheduled`（证书到期扫描）无 JobHandler → 整个 job 装配含 `/actuator/ppjob` 被跳过。前端已改为如实说明「该域未装配 JobHandler，合法状态非故障」

### 21.9 其余 P2 卡
多币种（`verify-multicurrency 134`）、营销抵扣编排（`verify-marketing-orch 323+30`）、账户生命周期（196）、多客户端（148）、灰度（状态写共享 Redis 全网关即时生效，不可达 fail-open 回稳定实例；**因数据保护未真启，页面如实展示未启用**）、可观测中心、调度作业、限流熔断、错误码表、分片影子比对。

### 21.10 收口挖出的 8 个真缺陷（**代码早已落盘、验证脚本从未跑过**）
> 教训：**源码写完 ≠ 验收完**。T-5/T-6 台账长期挂 ⬜ 是正确的；一跑从未跑过的 `verify-trade-plus.py`（10 段）即暴露 8 个真缺陷，其中 4 个属资金守恒/悬挂类。

| # | 缺陷 | 根因 | 修法 | 文件 |
|---|---|---|---|---|
| 1 | MERGE_COMBO 退款后**腿级 refundedAmount 恒 0**（主单与子订单分摊都对） | **口径不对称**：受理侧用 `isComboMode()`（含 MERGE_COMBO），完结侧硬编码 `"COMBO".equals()` → 叠加单整段腿级下推被跳过 | 完结侧改用 `isComboMode()` | `PaymentCompletionListener:114` |
| 2 | 分阶段**撤销后无法重付**（`Duplicate entry 'O-2' for uk_stage_order_seq`） | 期级唯一键挡住合法 INSERT | 降为普通索引 `idx_stage_order_seq`（三重双付守卫仍在）+ 幂等迁移 | `StagePayMapper` / `TradeSchemaUpgrade` / 三处 schema |
| 3 | 改价 **originalAmount 留痕被覆盖**为新价 | **MySQL UPDATE 赋值从左到右**，`amount` 先改导致 COALESCE 取新值 | 调换赋值顺序 | `OrderMapper.changePriceCas` |
| 4 | 合并**受理前失败悬挂 PAYING**（风控降级 503） | 守卫 `try` 从 `coordinator.begin` 才开始，风控检查在 try **之外** | try 边界前移覆盖「置 PAYING 之后、TCC 之前」整段；xid==null 统一清理 | `MergePayOrchestrator` |
| 5 | 监控每轮**逐条远程查对端自撞 429** | 76+ 次无间隔 `trade.query` 撞 Sentinel 热点 50 QPS/appId | feeCache + 每轮 20 条 + 60ms 间隔 + `feesComplete` 门禁 | ride `PaymentMonitor` |
| 6 | 清算跑批**误判 C0001/503** | provider 实测跑完 **10.9s** > Dubbo 默认 **8s** | **方法级** `@Method(name="runSettleBatch", timeout=120000)`，其余不放宽 | `SettleOpsController` |
| 7 | **TCC 回滚后合并单永久 PAYING** | `reopenMerge` 全仓仅 3 调用点全在 orchestrator，`onCancelled` **根本没有**；line 65 注释声称有实现却无 | `onCancelled` 按 afterCommit 模式补重开（CAS 幂等，失败由 PAYING 续驱兜底） | `PaymentCompletionListener` |
| 8 | 缺陷 7 修后竞态仍悬挂 | `reopenMerge` 要求**全部子订单必须 CREATED**，而竞态中一子订单**已到期 CLOSED** → 永远抛「子订单未释放」 | 「已释放」判定接受 **CLOSED 终态**（资金从未预留或已随回滚释放） | `MergePayService.reopenMerge` |

**第一版修法引入回归的教训（缺陷 2）**：最初用「CAS 复用 CANCELLED 留痕行」让重付通过，却破坏了 `releaseStageAfterCancel` 按旧 paymentId 的幂等重入（404 阶段留痕不存在）。**先读原设计意图再修**——原注释明写每次 attempt 独立留痕行，故正解是放宽唯一键而非复用行。

**验证夹具同步修严（非放宽阈值）**：
- `wait_payment` 原 `poll` 超时只返回末值不校验 → 会把 FAILED 单当成功传下游，产生误导性失败；改**严格断言终态**
- 长套件复用同一付款人（a1/a3）撞每 payer 10s/5 笔频率闸，导致**每轮崩在不同段、✓ 数跨轮不可比**；段6/段7/改价竞态各换**独立新付款账户**
- step-up 令牌 300s TTL 中途过期 → `api()` 补 403 A0403 自动续取
- 两处**断言本身写错**（非产品缺陷）：零悬挂允许集漏 `REFUNDED/PARTIAL_REFUNDED`（本就是终态）；`routedCount` 是列表 `[{'c':31}]` 却与整数 Σ 比（恒 False，数据实际正确）
- 收尾清理 `dissolve` 409 属合法拒绝（子订单未释放），改非致命不中断套件

### 21.11 P2 剩余
- **I-1 ZK 集群化**：计划标注「可选」，**用户批准不做**，列入生产部署必改清单（当前单节点）
- **I-6 tcc-core 参与者自注册**：待实现。现状 `TccParticipants` 硬编码 3 个 `@DubboReference`（accounting/channel/trade）+ `Map.of`，新增参与者域须改该类；目标按 Dubbo group 从注册中心自动发现

---

## 附录 A：关键代码索引

| 机制 | 位置 |
|---|---|
| TCC 协调器 | tcc-core/.../TccCoordinator.java（PaymentConfig 直接装配） |
| TCC 参与者模板 | tcc-core/.../TccParticipantTemplate.java |
| 幂等模板 | tcc-core/.../IdempotentTemplate.java |
| TCC Dubbo 桥接 | payment-payment/.../config/DubboTccParticipant.java；错误码映射在 payment-common/.../FacadeSupport.java |
| 支付编排 | payment-payment/.../service/PaymentService.java（pay :72 / refund :263） |
| 资金单（充值/提现） | payment-payment/.../service/PaymentService.java#fundPay :161（biz_type TOPUP/WITHDRAW，见 §17.1） |
| 事务性终态+outbox | payment-payment/.../service/PaymentCompletionListener.java（onConfirmed :44，5 参 insert + afterCommit 主投递） |
| outbox 轮询兜底 | payment-payment/.../service/OutboxDispatcher.java#dispatchPending :38 |
| outbox MQ 幂等投递 | payment-payment/.../service/OutboxDispatcher.java#deliverByEventBizId :73 |
| RocketMQ 主投递 | payment-payment/.../mq/RocketMqOutboxPublisher.java（OutboxPublisher 接口 / MqConfig 装配 / MqProperties 开关，docker only） |
| RocketMQ 消费 | payment-payment/.../mq/OutboxMessageListener.java（consumeMessage → deliverByEventBizId，失败 RECONSUME_LATER） |
| 双调度 | payment-payment/.../service/PaymentScheduler.java |
| 复式记账 | payment-accounting/.../service/AccountingService.java（post :203 / entriesPage :108） |
| 流水全局分页 | payment-accounting/.../mapper/EntryMapper.java（selectPage :25 / countPage :28，accountId 空=查总的） |
| 风控限流抽象 | payment-risk/.../service/RateLimiter.java（InMemoryRateLimiter local / RedisRateLimiter docker Lua ZSET） |
| 商户缓存 | payment-gateway/.../web/MerchantCache.java（docker Redis pp:openapi:app:<appId> TTL60s，读写异常降级直查） |
| 序列方言 | payment-common/.../db/H2Sequence.java（H2 CREATE ALIAS→next_value_for；MySQL sequences 表+存储函数 LAST_INSERT_ID） |
| MySQL DDL | 各域 resources/schema-mysql.sql（docker 一次性建表，sql.init.mode=never，见 §18.2） |
| H2→MySQL 迁移 | tools/MigrateH2ToMySQL.java + migrate-h2-to-mysql.sh（只读复制 H2，INSERT IGNORE 去重，序列 GREATEST 回填） |
| 订单状态机 | payment-trade/.../service/TradeService.java |
| 渠道状态机 | payment-channel/.../service/ChannelService.java |
| 通知推送 | payment-notify/.../service/NotifyPusher.java |
| 五方对账 | payment-recon/.../service/ReconService.java |
| OpenAPI 验签 | payment-gateway/.../web/OpenApiController.java |
| 错误映射 | payment-gateway/.../web/GlobalExceptionHandler.java |

## 附录 B：运行速查

```bash
cd /Users/hegan/Documents/Qoder/payment-pro
./run.sh                 # local：编译 + 启动 13 进程（--no-build 跳过编译）
./run.sh --docker        # docker：MySQL/RocketMQ/Redis/ZK 共享栈（先起 ops-pro 中间件）
./stop.sh                # 优雅停止（data/ 与 MySQL 数据保留）
open http://127.0.0.1:8080/
python3 smoke-test.py    # 79 项功能断言
python3 verify-pro.py    # 22 项并发/混沌/持久化断言
PP_PROFILE=docker python3 verify-pro.py   # docker profile 回归（需 source docker.env）
./migrate-h2-to-mysql.sh --dry-run        # 只读 H2→MySQL 迁移预演
```

---

## 附录 C：数据字典（字段级 DDL）

> 以下 DDL 逐字取自各域 `src/main/resources/schema.sql`（local H2），由 Spring SQL init 在启动时幂等执行（`CREATE ... IF NOT EXISTS`）。
> 通用约定：主键多为 `VARCHAR` 业务 ID（前缀 + 序列）；时间戳统一 `BIGINT` 毫秒；金额统一 `BIGINT` 分；JSON 载荷用 `CLOB`。
> **docker MySQL 方言**：各域另有 `schema-mysql.sql`（对空库执行一次，`sql.init.mode=never`），表结构/字段/索引与下文一致，差异仅在建表方言——`CLOB`→`LONGTEXT`、表尾 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`、`biz_type`/`out_trade_no` 等列内联；序列不再用 `CREATE SEQUENCE`，改为 `sequences(name PK, value)` 表 + 存储函数 `next_value_for`（`UPDATE ... LAST_INSERT_ID(value+1)`），与 H2 的 `CREATE ALIAS next_value_for FOR "...H2Sequence.next"` 同名同签名，故 mapper 取号 SQL `SELECT next_value_for('seq_x')` 两端零改动（见 §18.2）。
> 标注 legend：🔑 幂等/唯一键 ｜ 🔁 CAS 状态列 ｜ 🧊 冻结/余额权威 ｜ 📮 异步投递驱动列。

### C.1 member 库（payment-member）

```sql
CREATE SEQUENCE IF NOT EXISTS seq_member START WITH 1;
CREATE TABLE IF NOT EXISTS member (
    id         VARCHAR(32) PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    type       VARCHAR(16) NOT NULL,          -- USER / MERCHANT
    status     VARCHAR(16) NOT NULL,          -- ACTIVE / FROZEN
    app_id     VARCHAR(64),                   -- 商户 OpenAPI appId
    app_secret VARCHAR(64),                   -- 商户 HMAC 密钥（不出查询 DTO）
    created_at BIGINT      NOT NULL
);
```

| 列 | 说明 |
|---|---|
| id | "M"+seq_member；会员唯一标识 |
| name | 注册重名校验在 service 层（重名 409），表本身未加唯一约束 |
| type | 下单必须 MERCHANT；USER 下单 400 |
| app_id / app_secret | 仅 MERCHANT 持有；OpenAPI 验签凭据 |

### C.2 account 库（payment-account）

```sql
CREATE SEQUENCE IF NOT EXISTS seq_account START WITH 1;
CREATE TABLE IF NOT EXISTS account (
    id          VARCHAR(32) PRIMARY KEY,
    member_id   VARCHAR(32) NOT NULL,
    member_name VARCHAR(64),
    type        VARCHAR(16) NOT NULL,         -- USER / MERCHANT
    status      VARCHAR(16) NOT NULL,         -- ACTIVE / FROZEN
    created_at  BIGINT      NOT NULL,
    CONSTRAINT uk_account_member UNIQUE (member_id)   -- 🔑 一会员一账户
);
```

> 账户域**不存余额**（余额权威在账务域 account_balance）。`AccountDTO.balance` 是聚合查询时从账务域回填的展示字段，非本表列。

### C.3 accounting 库（payment-accounting）——资金权威

```sql
CREATE SEQUENCE IF NOT EXISTS seq_entry START WITH 1;
CREATE TABLE IF NOT EXISTS account_balance (
    account_id VARCHAR(32) PRIMARY KEY,
    balance    BIGINT NOT NULL DEFAULT 0,     -- 🧊 贷方累计 − 借方累计
    frozen     BIGINT NOT NULL DEFAULT 0,     -- 🧊 TCC Try 冻结额；可用 = balance − frozen
    version    INT    NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS ledger_entry (
    id            VARCHAR(32) PRIMARY KEY,    -- "E"+seq_entry
    seq           BIGINT       NOT NULL,      -- 全局分录序号（排序/审计）
    biz_no        VARCHAR(64)  NOT NULL,      -- 关联转账凭证
    account_id    VARCHAR(32)  NOT NULL,
    dir           VARCHAR(1)   NOT NULL,      -- D 借 / C 贷
    amount        BIGINT       NOT NULL,
    balance_after BIGINT       NOT NULL,      -- 过账后余额快照
    subject       VARCHAR(128),
    ref_id        VARCHAR(64),
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_entry_account ON ledger_entry (account_id, seq);
CREATE INDEX IF NOT EXISTS idx_entry_biz ON ledger_entry (biz_no);
CREATE TABLE IF NOT EXISTS transfer_record (
    biz_no     VARCHAR(64) PRIMARY KEY,       -- 🔑 过账幂等兜底（同 bizNo 不重复落凭证）
    lines      CLOB         NOT NULL,         -- 分录行 JSON
    subject    VARCHAR(128),
    ref_id     VARCHAR(64),
    reversed   BOOLEAN      NOT NULL DEFAULT FALSE,  -- 退款反向凭证标记
    created_at BIGINT       NOT NULL
);
CREATE TABLE IF NOT EXISTS tcc_branch_log (
    xid        VARCHAR(64) NOT NULL,
    branch_id  VARCHAR(80) NOT NULL,
    action     VARCHAR(32) NOT NULL,          -- PAY / REFUND
    status     VARCHAR(16) NOT NULL,          -- 🔁 TRYING / CONFIRMED / CANCELLED
    context    CLOB,
    created_at BIGINT      NOT NULL,
    updated_at BIGINT      NOT NULL,
    PRIMARY KEY (xid, branch_id, action)      -- 🔑 TCC 幂等/空回滚/防悬挂载体
);
CREATE TABLE IF NOT EXISTS idempotent_record (
    biz_type   VARCHAR(32) NOT NULL,          -- TOPUP（充值）/ POST（通用过账，v1.3）
    biz_no     VARCHAR(64) NOT NULL,
    result     CLOB,                          -- 占位 NULL → 业务后回填结果 JSON
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (biz_type, biz_no)            -- 🔑 通用业务幂等
);
```

### C.4 trade 库（payment-trade）

```sql
CREATE SEQUENCE IF NOT EXISTS seq_order START WITH 1;
CREATE TABLE IF NOT EXISTS trade_order (
    id              VARCHAR(32) PRIMARY KEY,  -- "O"+seq_order
    merchant_id     VARCHAR(32)  NOT NULL,
    merchant_name   VARCHAR(64),
    subject         VARCHAR(128) NOT NULL,
    amount          BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL,    -- 🔁 CREATED/PAYING/PAID/PARTIAL_REFUNDED/REFUNDED/CLOSED
    payment_id      VARCHAR(32),              -- 预留时写入；release 按此匹配防误释放
    refunded_amount BIGINT       NOT NULL DEFAULT 0,
    created_at      BIGINT       NOT NULL,
    paid_at         BIGINT,
    closed_at       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_order_merchant ON trade_order (merchant_id);
-- v1.6.0 商户外部交易号幂等；v1.7.0 单据类型
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS out_trade_no VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS idx_order_out_trade ON trade_order (merchant_id, out_trade_no);
ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS biz_type VARCHAR(16) NOT NULL DEFAULT 'TRADE';  -- TRADE/TOPUP/WITHDRAW
-- tcc_branch_log / idempotent_record 结构同 C.3（idempotent biz_type=REFUND_MARK）
```

### C.5 channel 库（payment-channel）

```sql
CREATE TABLE IF NOT EXISTS channel_trade (
    biz_no      VARCHAR(64) PRIMARY KEY,      -- 🔑 PAY=paymentId / REFUND=refundId
    type        VARCHAR(16) NOT NULL,         -- PAY / REFUND
    orig_biz_no VARCHAR(64),                  -- REFUND 指向原 PAY 交易
    card_no     VARCHAR(32) NOT NULL,         -- 库内全卡号（出参脱敏）
    amount      BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL,         -- 🔁 PREAUTH/CAPTURED/REFUNDED/VOIDED
    reason      VARCHAR(256),
    created_at  BIGINT      NOT NULL
);
-- tcc_branch_log 结构同 C.3（渠道无 idempotent_record，幂等全靠分支日志）
```

### C.6 payment 库（payment-payment）——编排中枢

```sql
CREATE SEQUENCE IF NOT EXISTS seq_payment START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_refund START WITH 1;
CREATE TABLE IF NOT EXISTS payment (
    id                  VARCHAR(32) PRIMARY KEY,  -- "P"+seq_payment
    order_id            VARCHAR(32)  NOT NULL,
    merchant_id         VARCHAR(32)  NOT NULL,
    method              VARCHAR(16)  NOT NULL,    -- BALANCE / CARD
    payer_account_id    VARCHAR(32),              -- BALANCE 付款户
    card_no             VARCHAR(32),              -- CARD 全卡号
    merchant_account_id VARCHAR(32)  NOT NULL,
    amount              BIGINT       NOT NULL,
    fee                 BIGINT       NOT NULL,    -- MoneyUtil.fee(amount, feeBps)
    status              VARCHAR(16)  NOT NULL,    -- 🔁 PROCESSING/SUCCESS/FAILED/REFUNDED
    refunded_amount     BIGINT       NOT NULL DEFAULT 0,
    refunded_fee        BIGINT       NOT NULL DEFAULT 0,
    channel_biz_no      VARCHAR(64),              -- CARD=paymentId
    fail_reason         VARCHAR(256),
    request_id          VARCHAR(64)  NOT NULL,    -- 🔑 入口幂等键
    xid                 VARCHAR(64),              -- 关联全局事务
    callback_url        VARCHAR(256),
    created_at          BIGINT       NOT NULL,
    CONSTRAINT uk_payment_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_payment_xid ON payment (xid);
CREATE INDEX IF NOT EXISTS idx_payment_order ON payment (order_id);
-- v1.7.0 单据类型：TRADE 商户交易（默认）/ TOPUP 充值 / WITHDRAW 提现
ALTER TABLE payment ADD COLUMN IF NOT EXISTS biz_type VARCHAR(16) NOT NULL DEFAULT 'TRADE';
CREATE TABLE IF NOT EXISTS refund_record (
    id         VARCHAR(32) PRIMARY KEY,       -- "R"+seq_refund
    payment_id VARCHAR(32) NOT NULL,
    amount     BIGINT      NOT NULL,
    fee_refund BIGINT      NOT NULL,          -- 按比例退手续费
    request_id VARCHAR(64) NOT NULL,          -- 🔑
    xid        VARCHAR(64),
    status     VARCHAR(16) NOT NULL,          -- 🔁 PROCESSING/SUCCESS/FAILED
    biz_no     VARCHAR(64) NOT NULL,          -- =refundId，账务转账号
    created_at BIGINT      NOT NULL,
    CONSTRAINT uk_refund_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_refund_xid ON refund_record (xid);
CREATE TABLE IF NOT EXISTS outbox (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event         VARCHAR(32)  NOT NULL,      -- SETTLE_PAY/SETTLE_REFUND/NOTIFY_PAY/NOTIFY_REFUND
    biz_id        VARCHAR(64)  NOT NULL,
    payload       CLOB         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- 🔁 📮 PENDING/DELIVERED/DEAD
    attempts      INT          NOT NULL DEFAULT 0,          -- 📮
    next_retry_at BIGINT       NOT NULL DEFAULT 0,          -- 📮 退避到期时间
    last_error    VARCHAR(256),
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (status, next_retry_at);
CREATE TABLE IF NOT EXISTS dtx_transaction (
    xid           VARCHAR(64) PRIMARY KEY,    -- "T"+UUID
    biz_type      VARCHAR(32) NOT NULL,       -- PAY / REFUND
    biz_no        VARCHAR(64) NOT NULL,       -- paymentId / refundId
    status        VARCHAR(16) NOT NULL,       -- 🔁 TRYING/CONFIRMING/CANCELLING/CONFIRMED/CANCELLED/DEAD
    fail_reason   VARCHAR(256),
    retry_count   INT         NOT NULL DEFAULT 0,           -- 📮
    next_retry_at BIGINT      NOT NULL DEFAULT 0,           -- 📮
    version       INT         NOT NULL DEFAULT 0,
    created_at    BIGINT      NOT NULL,
    updated_at    BIGINT      NOT NULL,
    CONSTRAINT uk_dtx_biz UNIQUE (biz_type, biz_no)         -- 🔑 一支付单不开两个全局事务
);
CREATE TABLE IF NOT EXISTS dtx_branch (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    xid         VARCHAR(64) NOT NULL,
    branch_id   VARCHAR(80) NOT NULL,         -- xid+"-B"+seq
    participant VARCHAR(32) NOT NULL,         -- accounting/channel/trade（路由 key）
    action      VARCHAR(32) NOT NULL,         -- PAY / REFUND
    status      VARCHAR(16) NOT NULL,         -- 🔁 INIT/TRY_OK/TRY_FAIL/CONFIRMED/CANCELLED
    context     CLOB,                         -- 分支上下文 JSON（重启可重放）
    seq         INT         NOT NULL,         -- 注册顺序（Try/Confirm/Cancel 按此序）
    CONSTRAINT uk_dtx_branch UNIQUE (xid, branch_id)
);
CREATE INDEX IF NOT EXISTS idx_dtx_recover ON dtx_transaction (status, next_retry_at);
```

> `dtx_branch`（协调器侧，含 participant/seq）与 `tcc_branch_log`（参与者侧，含 action 主键）是**两张不同的表**，分别落在协调器库与参与者库，切勿混淆。

### C.7 settle 库（payment-settle）

```sql
CREATE SEQUENCE IF NOT EXISTS seq_settle START WITH 1;
CREATE TABLE IF NOT EXISTS settle_record (
    id          VARCHAR(32) PRIMARY KEY,      -- "S"+seq_settle
    type        VARCHAR(16) NOT NULL,         -- PAY / REFUND
    biz_id      VARCHAR(64) NOT NULL,         -- PAY=paymentId / REFUND=refundId
    payment_id  VARCHAR(32) NOT NULL,
    order_id    VARCHAR(32),
    merchant_id VARCHAR(32) NOT NULL,
    gross       BIGINT      NOT NULL,         -- 总额
    fee         BIGINT      NOT NULL,         -- 手续费
    net         BIGINT      NOT NULL,         -- 净额 = gross − fee
    method      VARCHAR(16),
    status      VARCHAR(16) NOT NULL,
    created_at  BIGINT      NOT NULL,
    CONSTRAINT uk_settle_biz UNIQUE (biz_id, type)          -- 🔑 结算去重兜底
);
CREATE INDEX IF NOT EXISTS idx_settle_merchant ON settle_record (merchant_id);
-- idempotent_record 结构同 C.3（biz_type=SETTLE_PAY / SETTLE_REFUND）
```

### C.8 notify 库（payment-notify）

```sql
CREATE SEQUENCE IF NOT EXISTS seq_notify START WITH 1;
CREATE TABLE IF NOT EXISTS notify_record (
    id            VARCHAR(32) PRIMARY KEY,    -- "N"+seq_notify
    event         VARCHAR(32)  NOT NULL,      -- PAY_SUCCESS / REFUND_SUCCESS
    biz_id        VARCHAR(64)  NOT NULL,
    payload       CLOB         NOT NULL,
    callback_url  VARCHAR(256),               -- 空 → PULL 模式
    status        VARCHAR(16)  NOT NULL,      -- 🔁 📮 PULL/PENDING/DELIVERED/FAILED
    attempts      BIGINT       NOT NULL DEFAULT 0,          -- 📮
    next_retry_at BIGINT       NOT NULL DEFAULT 0,          -- 📮
    last_error    VARCHAR(256),
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notify_biz ON notify_record (biz_id);
CREATE INDEX IF NOT EXISTS idx_notify_pending ON notify_record (status, next_retry_at);
-- idempotent_record 结构同 C.3（biz_type=NOTIFY，biz_no=event:bizId）
```

### C.9 recon 库（payment-recon）

```sql
CREATE SEQUENCE IF NOT EXISTS seq_recon START WITH 1;
CREATE TABLE IF NOT EXISTS recon_report (
    id         VARCHAR(32) PRIMARY KEY,       -- "RC"+seq_recon
    checked_at BIGINT  NOT NULL,
    pass       BOOLEAN NOT NULL,              -- 六项全通过才 true
    report     CLOB    NOT NULL               -- 逐项核对明细 JSON
);
```

### C.10 跨库幂等键全景

| 域 | 表 | 幂等键 | 吸收的重复来源 |
|---|---|---|---|
| payment | payment | request_id UNIQUE | 客户端重试/并发同 requestId |
| payment | refund_record | request_id UNIQUE | 退款重试 |
| payment | dtx_transaction | (biz_type,biz_no) UNIQUE | 同支付单重复开启全局事务 |
| accounting/channel/trade | tcc_branch_log | (xid,branch_id,action) PK | 补偿重试/网络重发/重启重放 |
| accounting | transfer_record | biz_no PK | 过账兜底 |
| accounting | idempotent_record | (TOPUP,bizNo)/(POST,bizNo) | 充值/通用过账重试 |
| trade | trade_order | (merchant_id,out_trade_no) UNIQUE | 同商户同 outTradeNo 重复下单（回放既有订单） |
| trade | idempotent_record | (REFUND_MARK,bizNo) | 退款标记重投 |
| settle | idempotent_record + uk_settle_biz | (SETTLE_PAY,paymentId)/(SETTLE_REFUND,bizId) | outbox 至少一次重投 |
| notify | idempotent_record | (NOTIFY,event:bizId) | outbox 至少一次重投 |

---

## 附录 D：接口契约清单

> 所有 facade 方法返回 `Result<T>`（`{code,message,data}`）；消费端 `.orThrow()` 把非 00000 转 `BizException`。
> 错误码：A0400 参数 / A0404 不存在 / A0409 冲突 / B0001 业务拒绝 / B0002 系统 / C0001 下游不可达。
> 下表「幂等键」列为空表示该方法是只读查询或天然幂等（状态 CAS）。

### D.1 MemberFacade（group=默认）

| 方法 | 入参 | 返回 data | 主要错误 | 幂等键 |
|---|---|---|---|---|
| create | MemberCreateRequest{name,type} | MemberDTO | A0400 参数；A0409 重名 | — |
| list | — | List\<MemberDTO> | — | 只读 |
| get | memberId | MemberDTO | A0404 | 只读 |
| getByAppId | appId | MemberDTO | A0404 | 只读（OpenAPI 验签用） |
| updateStatus | memberId, status | MemberDTO | A0404 | CAS |

`MemberDTO`：id/name/type/status/appId/appSecret/createdAt。

### D.2 AccountFacade

| 方法 | 入参 | 返回 data | 主要错误 | 幂等键 |
|---|---|---|---|---|
| create | memberId | AccountDTO | A0404 会员不存在；A0409 已开户 | uk_account_member |
| list | memberId | List\<AccountDTO> | — | 只读 |
| get | accountId | AccountDTO（含回填 balance） | A0404 | 只读 |
| getByMember | memberId | AccountDTO | A0404 | 只读 |
| updateStatus | accountId, status | AccountDTO | A0404 | CAS |
| check | List\<accountIds> | Map\<id,AccountStatusDTO{status,type}> | — | 只读（账务过账前批量校验） |

### D.3 AccountingFacade

| 方法 | 入参 | 返回 data | 主要错误 | 幂等键 |
|---|---|---|---|---|
| topup | TopupRequest{accountId,amount,bizNo} | Long（充值后余额） | A0400 amount/bizNo；A0404 账户；B0001 冻结 | (TOPUP,bizNo) |
| post | PostRequest{bizNo,subject,refId,lines} | Void | A0400 借贷不平/账户；B0001 冻结/余额不足 | (POST,bizNo) |
| balance | accountId | Long | — | 只读 |
| balances | List\<accountIds> | Map\<id,Long> | — | 只读 |
| entries | accountId, limit | List\<EntryDTO> | — | 只读（limit≤500） |
| entriesPage | EntryQuery{accountId,bizNo,dir,keyword,page,size} | PageDTO\<EntryDTO> | — | 只读（accountId 空=跨账户查总的；size≤200，默认 20） |
| transfer | bizNo | TransferDTO | A0404 | 只读 |
| trial | — | TrialDTO{totalDebit,totalCredit,balanced,balanceSum,accounts,entries} | — | 只读 |

`EntryDTO`：id/bizNo/accountId/dir/amount/balanceAfter/subject/refId/createdAt。
`TransferDTO`：bizNo/lines[TransferLineDTO{accountId,dir,amount}]/subject/refId/reversed/createdAt。
`PostRequest.lines`：List\<TransferLineDTO>，通用过账（v1.3，转账/提现镜像走此入口），借贷净额须为 0。
`EntryQuery`：page 默认 1（`Math.max(page,1)`）、size 默认 20（≤0 取 20，上限 200）、`getOffset()=(page-1)*size`；dir=D/C 筛选，keyword 模糊匹配 subject/bizNo。
`PageDTO<T>`：items/page/size/total(long)，`static of(items,page,size,total)`。

### D.4 TradeFacade

| 方法 | 入参 | 返回 data | 主要错误 | 幂等键 |
|---|---|---|---|---|
| createOrder | OrderCreateRequest{merchantId,subject,amount,outTradeNo?,bizType?} | OrderDTO | A0400 非商户/subject；B0001 商户冻结 | (merchantId,outTradeNo) UNIQUE 回放既有订单 |
| listOrders | — | List\<OrderDTO> | — | 只读 |
| getOrder | orderId | OrderDTO | A0404 | 只读 |
| closeOrder | orderId | OrderDTO | A0409 非 CREATED | CAS |
| refundMark | orderId, amount, bizNo | OrderDTO | A0409 未支付；B0001 超退 | (REFUND_MARK,bizNo) |

`OrderDTO`：id/merchantId/merchantName/outTradeNo/bizType/subject/amount/status/paymentId/refundedAmount/createdAt/paidAt/closedAt。

### D.5 PaymentFacade

| 方法 | 入参 | 返回 data | 主要错误 | 幂等键 |
|---|---|---|---|---|
| pay | PayRequest{orderId,method,accountId,cardNo,requestId,callbackUrl} | PayResultDTO{payment,repeated} | A0400 requestId/method/账户/卡号；A0409 订单状态；B0001 余额不足/冻结/风控/渠道 | request_id |
| fundPay | FundPayRequest{bizType,accountId,amount,requestId,callbackUrl} | PayResultDTO{payment,repeated} | A0400 bizType/requestId/amount；A0404 账户；B0001 余额不足（WITHDRAW）/冻结 | request_id |
| refund | paymentId, RefundRequest{amount,requestId} | RefundResultDTO{payment,refundAmount,refundId} | A0400 amount；A0409 未成功；B0001 超退 | request_id |
| listPayments | — | List\<PaymentDTO> | — | 只读 |
| getPayment | paymentId | PaymentDTO | A0404 | 只读（轮询终态用） |

`PaymentDTO`：id/orderId/bizType/method/payerAccountId/cardNo(脱敏)/merchantAccountId/amount/fee/status/refundedAmount/refundedFee/channelBizNo/failReason/requestId/xid/createdAt。
`FundPayRequest`：bizType=TOPUP/WITHDRAW、accountId、amount(Long 分)、requestId（幂等键）、callbackUrl；充值/提现走完整 TCC 生成交易单+支付单（SYS_FUND 系统资金商户、fee=0、BALANCE、无渠道），见 §17.1。
> pay/refund/fundPay 成功返回时支付单多为 PROCESSING（二阶段异步），客户端轮询 getPayment 至 SUCCESS/FAILED。`repeated=true` 表示幂等回放。资金单（TOPUP/WITHDRAW）不可退款，仅 TRADE 可退。

### D.6 CashierFacade

| 方法 | 入参 | 返回 data | 说明 |
|---|---|---|---|
| cashier | orderId | CashierDTO{order,methods[PayMethodDTO{method,name,hint,accounts}]} | 聚合订单+可用支付方式 |
| pay | PayRequest | PayResultDTO | 透传支付核心 |

### D.7 ChannelFacade / RiskFacade

| 方法 | 入参 | 返回 data | 说明 |
|---|---|---|---|
| ChannelFacade.listTrades | — | List\<ChannelTradeDTO> | 只读；渠道写操作仅经 TCC 参与者接口 |
| RiskFacade.check | RiskCheckRequest{payerId,merchantId,amount,method} | RiskResultDTO{pass,reason} | 限额+限频；拒绝 pass=false |

`ChannelTradeDTO`：bizNo/type/origBizNo/cardNo/amount/status/reason/createdAt。

### D.8 SettleFacade / NotifyFacade / ReconFacade

| 方法 | 入参 | 返回 data | 幂等键 |
|---|---|---|---|
| SettleFacade.recordPay | SettlePayRequest{paymentId,orderId,merchantId,amount,fee,method} | SettleRecordDTO | (SETTLE_PAY,paymentId) |
| SettleFacade.recordRefund | SettleRefundRequest{bizId,paymentId,amount,feeRefund} | SettleRecordDTO | (SETTLE_REFUND,bizId) |
| SettleFacade.records | merchantId | List\<SettleRecordDTO> | 只读 |
| SettleFacade.summary | — | SettleSummaryDTO{payCount,totalGross,totalFee,totalNet,totalRefund} | 只读 |
| NotifyFacade.send | NotifyRequest{event,bizId,payload,callbackUrl} | NotifyRecordDTO | (NOTIFY,event:bizId) |
| NotifyFacade.records | bizId | List\<NotifyRecordDTO> | 只读 |
| NotifyFacade.get | notifyId | NotifyRecordDTO | 只读 |
| ReconFacade.run | — | ReconReportDTO{id,checkedAt,pass,checks[ReconCheckDTO{name,pass,detail}]} | 每次新报告 |
| ReconFacade.latest | — | ReconReportDTO | 只读 |

### D.9 TccParticipantFacade（group=accounting/channel/trade）

| 方法 | 入参 | 返回 | 语义 |
|---|---|---|---|
| tryAction | TccBranchRequest{xid,branchId,action,context} | Result\<Void> | 一阶段预留；失败协调器全局 Cancel |
| confirm | 同上 | Result\<Void> | 二阶段提交；幂等可重试 |
| cancel | 同上 | Result\<Void> | 二阶段回滚；处理空回滚/防悬挂 |

> 同一接口按 Dubbo group 区分三套实现，协调器 `TccParticipants.asMap()` 按 participant 名路由。context 为业务 JSON：accounting→AccountingTccContext、channel→ChannelTccContext、trade→TradeTccContext。

### D.10 HealthFacade（group=${spring.application.name}）

| 方法 | 返回 | 说明 |
|---|---|---|
| ping | Result\<String> | 逐域探活；网关按 group 精确引用聚合 13/13 |

---

## 附录 E：核心代码走读

> 代码片段逐字摘自源码（略去日志/import），行号为真实位置。路径相对仓库根 `payment-pro/`。
> 阅读顺序建议：E.1→E.4 协调器，E.5→E.7 参与者与幂等模板，E.8→E.10 支付编排与 outbox，E.11→E.12 账务与装配。

### E.1 开启全局事务与注册分支 — `tcc-core/.../TccCoordinator.java:41`

```java
public String begin(String bizType, String bizNo) {
    String xid = "T" + UUID.randomUUID().toString().replace("-", "");
    try {
        jdbc.update("INSERT INTO dtx_transaction(...,status,...) VALUES(?,?,?,?,...)",
                xid, bizType, bizNo, GlobalStatus.TRYING.name(), ...);
    } catch (DuplicateKeyException e) {                       // :48
        throw new TccException(TccException.CONFLICT, "分布式事务已存在: " + bizType + "/" + bizNo);
    }
    return xid;
}
public String registerBranch(String xid, String participant, String action, Object context) {  // :54
    Integer seq = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM dtx_branch WHERE xid=?", ...);
    String branchId = xid + "-B" + seq;
    jdbc.update("INSERT INTO dtx_branch(...,status,context,seq) VALUES(?,?,?,?,?,?,?)",
            xid, branchId, participant, action, BranchStatus.INIT.name(), TccContextUtil.toJson(context), seq);
    return branchId;
}
```

**为什么这样写**：
- `begin` 用 `(biz_type,biz_no)` 唯一约束做兜底——即使上层幂等失效，同一支付单也开不出第二个全局事务（组件抛 CONFLICT，FacadeSupport 边界映射为 A0409）。
- 分支 `context` 序列化成 JSON **落库**而非放内存：协调器重启后 `recover()` 能从库里读出原样上下文重放二阶段，这是崩溃恢复的前提。
- `seq` 决定 Try/Confirm/Cancel 的执行顺序，注册顺序即驱动顺序。

### E.2 一阶段同步 Try — `tcc-core/.../TccCoordinator.java:63`

```java
public void tryAll(String xid) {
    String failReason = null;
    for (BranchRow b : branches(xid)) {                       // 按 seq 序
        try {
            participant(b).tryAction(request(b));             // SPI 直接抛异常，无 Result 包装
            updateBranch(b, BranchStatus.TRY_OK);
        } catch (Exception e) {
            updateBranch(b, BranchStatus.TRY_FAIL);
            failReason = e.getMessage();
            break;                                            // :73 首个失败即停
        }
    }
    if (failReason != null) {
        jdbc.update("UPDATE dtx_transaction SET status='CANCELLING', fail_reason=? ...", ...);
        driveCancel(xid);                                     // :79 立即同步回滚
        throw new TccException(TccException.BIZ_ERROR, failReason);  // :80 抛给调用方（FacadeSupport 转 B0001）
    }
}
```

**为什么这样写**：Try 必须同步——余额不足/渠道拒绝要立刻给用户明确错误。任一失败立即 `driveCancel` 把已预留资源释放（未 Try 的分支走空回滚），再抛业务异常。失败分支之后的分支不再 Try（break），减少无谓预留。

### E.3 二阶段驱动与事务性终态 — `tcc-core/.../TccCoordinator.java:136`

```java
private void driveConfirm(String xid) {
    for (BranchRow b : branches(xid)) {
        if (b.status() == BranchStatus.CONFIRMED) continue;   // :138 分支级断点续传
        if (b.status() != BranchStatus.TRY_OK)                // :141 未就绪不允许提交
            throw new TccException(TccException.BIZ_ERROR, "提交阶段存在未就绪分支: ...");
        try {
            participant(b).confirm(request(b));
            updateBranch(b, BranchStatus.CONFIRMED);
        } catch (Exception e) {
            return;                                          // :149 失败留给补偿，不抛
        }
    }
    tx.executeWithoutResult(s -> {                            // :152 ★ 同一本地事务
        updateGlobal(xid, GlobalStatus.CONFIRMED);            //   全局终态
        if (listener != null) listener.onConfirmed(xid);      //   支付单翻转 + outbox 写入
    });
}
```

**为什么这样写**：这是事务性 outbox 的根基。全局终态、支付单 SUCCESS、outbox 两条消息在**一个本地事务**里提交——不存在"事务已提交但消息丢了"或"消息发了但状态没落"的中间态。Confirm 失败 `return` 而非抛异常，让 `bumpRetryIfPending` 登记退避，由 `recover()` 续驱；已 CONFIRMED 分支跳过实现断点续传。`driveCancel`（:161）对称，回调 `onCancelled` 落 FAILED。

### E.4 补偿恢复与退避耗尽 — `tcc-core/.../TccCoordinator.java:99` / `:184`

```java
public void recover() {
    List<Map<String,Object>> due = jdbc.queryForList(
        "SELECT xid,status FROM dtx_transaction WHERE status IN ('TRYING','CONFIRMING','CANCELLING') AND next_retry_at <= ?", now);
    for (var row : due) {
        switch (status) {
            case CONFIRMING -> driveConfirm(xid);
            case CANCELLING -> driveCancel(xid);
            case TRYING -> {                                 // :110 一阶段崩溃决策
                boolean allOk = !bs.isEmpty() && bs.stream().allMatch(b -> b.status()==TRY_OK);
                if (allOk) commitAsync(xid);                 //   全 Try 成功 → 提交
                else { /* 置 CANCELLING */ driveCancel(xid); } //   否则 → 回滚
            }
        }
        bumpRetryIfPending(xid);
    }
}
private void bumpRetryIfPending(String xid) {                 // :184
    if (终态) return;
    int count = retry_count + 1;
    if (count >= MAX_RETRY) {                                // :196 MAX_RETRY=8
        jdbc.update("UPDATE dtx_transaction SET status='DEAD' ...");  // 耗尽置 DEAD
        log.error("TCC 补偿重试耗尽，置 DEAD 待人工介入 xid={} retry={}", ...);
        return;
    }
    long delay = BACKOFF_SECONDS[Math.min(count-1, len-1)] * 1000L;  // {5,10,20,40,80,160,320,640}
    jdbc.update("UPDATE ... SET retry_count=?, next_retry_at=? ...", count, now+delay);
}
```

**为什么这样写**：TRYING 态恢复是协调器一阶段崩溃的兜底——分支日志持久化使得"全 TRY_OK 则提交、否则回滚"可判定。退避累计约 21 分钟，耗尽不无限重试而是显式置 DEAD，把无法自动收敛的事务暴露给对账与人工，避免静默吞掉资金不一致。

### E.5 参与者 Try：幂等/防悬挂/失败清理 — `tcc-core/.../TccParticipantTemplate.java:36`

```java
public void tryAction(TccBranchRequest req, Runnable business) {
    tx.executeWithoutResult(s -> {                           // REQUIRES_NEW（ctor :33）
        try {
            insertLog(req, ParticipantStatus.TRYING);
        } catch (DuplicateKeyException e) {
            ParticipantStatus existing = currentStatus(req);
            if (existing == ParticipantStatus.CANCELLED)      // :42 防悬挂
                throw new TccException(TccException.CONFLICT, "TCC 悬挂拒绝: ...");
            return;                                          // :46 幂等命中，不重复执行
        }
        try {
            business.run();
        } catch (RuntimeException e) {
            jdbc.update("DELETE FROM tcc_branch_log WHERE xid=? AND branch_id=? AND action=?", ...);  // :52
            throw e;                                         // 未预留资源 = 无日志
        }
    });
}
```

**为什么这样写**：
- 主键 `(xid,branch_id,action)` 冲突即幂等命中，直接返回。
- 冲突且既有 CANCELLED → 防悬挂：被空回滚过的分支，迟到的 Try 不能再预留资源。
- 业务失败时**同事务删日志**：若保留 TRYING 僵尸日志，后续 Cancel 会误执行解冻/撤销。删除后 Cancel 走空回滚，语义一致。
- 日志与业务同 REQUIRES_NEW 事务，原子提交。

### E.6 参与者 Confirm/Cancel：空回滚与状态互斥 — `tcc-core/.../TccParticipantTemplate.java:59` / `:76`

```java
public void confirm(req, business) {
    tx.executeWithoutResult(s -> {
        ParticipantStatus status = currentStatus(req);
        if (status == null) throw ...("TCC Confirm 无 Try 记录");      // :62 不允许凭空提交
        if (status == CONFIRMED) return;                              // :65 幂等
        if (status == CANCELLED) throw ...("已回滚分支不可提交");       // :68 互斥
        business.run(); updateStatus(req, CONFIRMED);
    });
}
public void cancel(req, business) {
    tx.executeWithoutResult(s -> {
        ParticipantStatus status = currentStatus(req);
        if (status == null) { insertLog(req, CANCELLED); return; }    // :79 ★ 空回滚
        if (status == CANCELLED) return;                              // :84 幂等
        if (status == CONFIRMED) throw ...("已提交分支不可回滚");       // :87 互斥
        business.run(); updateStatus(req, CANCELLED);
    });
}
```

**为什么这样写**：空回滚（Cancel 先于 Try 到达）时查无日志，落 CANCELLED 标记不动业务——这个标记同时成为防悬挂屏障。Confirm/Cancel 的状态互斥保护杜绝"已回滚又提交""已提交又回滚"的资金错乱。

### E.7 通用幂等模板 — `tcc-core/.../IdempotentTemplate.java:30`

```java
public <T> T execute(String bizType, String bizNo, Class<T> resultType, Supplier<T> action) {
    return tx.execute(s -> {
        try {
            jdbc.update("INSERT INTO idempotent_record(biz_type,biz_no,result,created_at) VALUES(?,?,NULL,?)", ...);  // 占位
        } catch (DuplicateKeyException e) {
            String json = /* SELECT result */;
            if (json == null) throw new TccException(TccException.CONFLICT, "幂等请求处理中: ...");  // :40 首请求仍在处理
            return TccContextUtil.fromJson(json, resultType);                          // :43 命中既有结果
        }
        T result = action.get();                                                       // :45 业务
        jdbc.update("UPDATE idempotent_record SET result=? WHERE ...", toJson(result)); // :46 回填
        return result;                                                                 // 同事务提交
    });
}
```

**为什么这样写**：占位（result=NULL）、业务、回填三步同事务。并发重复请求在唯一键上竞争：胜者执行业务并回填，败者要么读到完整结果（结果一致），要么读到 NULL 得到明确"处理中"（A0409），**绝不会重复执行业务**。相比"先查后插"消除了查-插窗口竞态。

### E.8 支付编排流水线 — `payment-payment/.../service/PaymentService.java:72`

```java
public PayResultDTO pay(PayRequest request) {
    requireRequestId(request.getRequestId());                 // :73 幂等键必填
    // method 校验 BALANCE/CARD                                 :75
    PaymentDO replay = paymentMapper.selectByRequestId(...);  // :78 ★ 回放先于状态检查
    if (replay != null) return result(replay, true);
    OrderDTO order = tradeFacade.getOrder(...).orThrow();     // :83 订单 CREATED 校验
    // BALANCE: 付款户 ACTIVE + 余额足额 / CARD: 卡号必填        :88-101
    long fee = MoneyUtil.fee(amount, feeBps);                 // :104 默认 60bps
    payment.setBizType("TRADE");                              // :109 商户交易（资金单走 fundPay 置 TOPUP/WITHDRAW）
    try { paymentMapper.insert(payment); }                    // :124 insert-first
    catch (DuplicateKeyException e) { return result(selectByRequestId(...), true); }  // :125 并发败者读胜者
    RiskResultDTO riskResult = riskFacade.check(risk).orThrow();  // :136
    if (!pass) { paymentMapper.finish(id,"FAILED",reason); throw ...; }  // :138 风控拒绝留痕
    String xid = coordinator.begin("PAY", payment.getId());   // :143
    paymentMapper.updateXid(payment.getId(), xid);            // :144
    coordinator.registerBranch(xid,"accounting","PAY", accountingContext(...));   // :147
    if (CARD) coordinator.registerBranch(xid,"channel","PAY", channelContext(...)); // :149
    coordinator.registerBranch(xid,"trade","PAY", tradeContext(...));             // :151
    coordinator.tryAll(xid);                                  // :152 同步 Try
    coordinator.commitAsync(xid);                             // :153 异步二阶段
    return result(require(payment.getId()), false);           // :154 立即返回 PROCESSING
}
```

**为什么这样写**：
- 幂等预检（:78）先于订单状态检查——订单已 PAID 后同 requestId 回放仍返回原始结果而非 409（实测修复过的缺陷）。
- insert-first（:124）+ DuplicateKey 回读（:125）：并发同 requestId 在唯一键竞争，败者读胜者结果。
- 分支注册顺序 accounting→channel→trade：最可能失败的资金约束前置，Try 失败回滚成本最小。
- 风控拒绝先把支付单落 FAILED 留痕再抛错，便于审计与对账。
- 充值/提现走 `fundPay()`（:161）：同样 requireRequestId + 完整 TCC（账务+交易分支）+ 完结监听，但 bizType=TOPUP/WITHDRAW、SYS_FUND 系统资金商户、fee=0、BALANCE、无渠道分支，故清结算单/对账自然齐备（见 §17.1）。

退款手续费按比例退（`PaymentService.java:280-282`）：

```java
long feeRefund = amount == remaining
        ? payment.getFee() - payment.getRefundedFee()                       // 最后一笔退剩余全部手续费
        : (payment.getFee() * amount + payment.getAmount() / 2) / payment.getAmount();  // 四舍五入
```

保证分账精确闭环、无一分钱残留。

### E.9 事务性终态回调 — `payment-payment/.../service/PaymentCompletionListener.java:44`

```java
public void onConfirmed(String xid) {                         // :44 在 driveConfirm 的同一本地事务内被调用
    long now = System.currentTimeMillis();
    long nextRetryAt = mqProps.isEnabled() ? now + mqProps.getFallbackGraceMs() : 0L;  // :46 MQ 模式留宽限期（默认 4000ms）
    OutboxPublisher publisher = mqProps.isEnabled() ? publisherProvider.getIfAvailable() : null;  // :47 docker only
    List<Runnable> publishes = new ArrayList<>();             // :48 收集 afterCommit 主投递任务

    PaymentDO payment = paymentMapper.selectByXid(xid);       // :50
    if (payment != null && "PROCESSING".equals(payment.getStatus())) {
        paymentMapper.finish(payment.getId(), "SUCCESS", null);                          // :52 支付单翻转
        outboxMapper.insert("SETTLE_PAY", payment.getId(), settlePayload, nextRetryAt, now);  // :62 ★ 5 参（含 next_retry_at）
        capture(publishes, publisher, "SETTLE_PAY", payment.getId(), settlePayload);          // :63
        outboxMapper.insert("NOTIFY_PAY", payment.getId(), notifyPayload, nextRetryAt, now);  // :71
        capture(publishes, publisher, "NOTIFY_PAY", payment.getId(), notifyPayload);          // :72
    }
    RefundDO refund = refundMapper.selectByXid(xid);          // :75
    if (refund != null && "PROCESSING".equals(refund.getStatus())) {
        refundMapper.finish(refund.getId(), "SUCCESS");       // :77
        paymentMapper.updateRefunded(pay.getId(), refundedAmount+..., refundedFee+...);  // :79 累计退款
        outboxMapper.insert("SETTLE_REFUND", ..., nextRetryAt, now);   // :89 + capture :90
        outboxMapper.insert("NOTIFY_REFUND", ..., nextRetryAt, now);   // :97 + capture :98
    }
    if (!publishes.isEmpty()) {                               // :102 事务提交后才发 MQ
        if (TransactionSynchronizationManager.isSynchronizationActive())
            registerSynchronization(afterCommit -> publishes.forEach(Runnable::run));  // :104-109
        else publishes.forEach(Runnable::run);                // :111 无事务上下文则直接发
    }
}
```

**为什么这样写**：
- `"PROCESSING".equals(status)` 守卫保证回调重放幂等——已是终态则跳过。支付单翻转与 outbox 写入和全局终态同本地事务（E.3），三者原子。退款额外累计 refunded_amount/refunded_fee，供后续退款比例计算与终态翻转。
- **5 参 insert 的 `nextRetryAt`**：local=0（轮询立即可投）；docker=now+宽限期（`payment.mq.fallback-grace-ms`，默认 4000ms）。宽限期内轮询 `selectPending(now,...)` 不会选中该行，把首投让给 afterCommit 的 MQ 主投递；MQ 发送失败或超宽限仍未 DELIVERED，轮询才兜底——避免 MQ 与轮询短时间内重复投同一事件。
- **afterCommit 才 publish**（:102-113）：MQ 消息只在本地事务提交后发出，杜绝"事务可能回滚却已发消息"。`RocketMqOutboxPublisher.publish`（:25）发送失败被吞掉（:35-37，事务已提交不能抛），outbox 行仍 PENDING 交轮询兜底——MQ 只是把投递从轮询加速为推送，不改变可靠性语义（见 §18.3）。
- `capture`（:130-135）仅在 publisher 非空（docker）时收集任务；local 无 MQ，publishes 为空，纯靠轮询。

### E.10 outbox 可靠投递 — `payment-payment/.../service/OutboxDispatcher.java:38`

```java
public void dispatchPending() {
    List<OutboxDO> pending = outboxMapper.selectPending(now, 50);           // 批量 50
    for (OutboxDO message : pending) {
        try { deliver(message); outboxMapper.markDelivered(message.getId()); }
        catch (Exception e) {
            int attempts = message.getAttempts() + 1;
            if (attempts >= MAX_ATTEMPTS) {                                 // :47 MAX=8
                outboxMapper.markDead(message.getId(), error);              // 耗尽 DEAD
            } else {
                long delay = BACKOFF_SECONDS[Math.min(attempts-1, len-1)] * 1000L;  // {3,6,12,24,48,96,192,384}
                outboxMapper.markRetry(message.getId(), attempts, now+delay, error);
            }
        }
    }
}
private void deliver(OutboxDO m) {                                          // :59
    switch (m.getEvent()) {
        case "SETTLE_PAY" -> settleFacade.recordPay(fromJson(...)).orThrow();
        case "SETTLE_REFUND" -> settleFacade.recordRefund(fromJson(...)).orThrow();
        case "NOTIFY_PAY","NOTIFY_REFUND" -> notifyFacade.send(fromJson(...)).orThrow();
        default -> throw new IllegalStateException("未知 outbox 事件: " + m.getEvent());  // :65
    }
}

// MQ 主投递入口（docker）：OutboxMessageListener.consumeMessage(:32) 解析 {event,bizId} 后调用
public void deliverByEventBizId(String event, String bizId) {               // :73
    OutboxDO m = outboxMapper.selectByEventBizId(event, bizId);             // :74
    if (m == null || !"PENDING".equals(m.getStatus())) return;             // :75 已 DELIVERED/DEAD 或不存在则跳过（幂等守卫）
    deliver(m);                                                            // :78 复用同一幂等 Dubbo 投递
    outboxMapper.markDelivered(m.getId());                                 // :79
}
```

**为什么这样写**：至少一次投递；接收端 settle/notify 用 IdempotentTemplate 去重 → 恰好一次效果。投递失败退避重试，耗尽 DEAD 由对账"支付-清结算一致"项精确报出缺失笔数。双调度 `PaymentScheduler.java:16`（recover 5s）/`:21`（dispatch 3s）均 `fixedDelay`，单实例无重叠。
**MQ 与轮询共用同一投递逻辑**：docker 模式 `OutboxMessageListener`（`@ConditionalOnProperty payment.mq.enabled=true`）消费 `PP_OUTBOX`，调 `deliverByEventBizId` 走与轮询完全相同的 `deliver`+`markDelivered`；`!PENDING` 守卫使 MQ 重投/轮询兜底/宽限期后重投都不会重复投递。消费抛错返回 `RECONSUME_LATER`（:40）由 broker 重投，轮询同时兜底；成功 `CONSUME_SUCCESS`（:43）。故 MQ 宕机/消费失败不丢消息，最坏退化为纯轮询（见 §18.3）。

### E.11 账务复式记账 — `payment-accounting/.../service/AccountingService.java:203`

```java
public void post(String bizNo, List<TransferLineDTO> lines, String subject, String refId, boolean reversed) {  // :203
    validateLines(lines);                                     // :204 借贷平衡强校验（D==C，def :252）
    checkAccounts(...);                                       // :205 非系统户 ACTIVE 校验（def :277）
    for (TransferLineDTO line : sorted(lines)) {              // :207 ★ 按 accountId 排序加锁 → 无死锁（sorted def :318）
        BalanceDO balance = lockOrCreate(line.getAccountId());  // :208 SELECT FOR UPDATE
        if ("D".equals(line.getDir())) {
            long available = balance.getBalance() - balance.getFrozen();
            if (!isSystem(...) && available < amount) throw ...("可用余额不足");  // :213-214
            newBalance = balance.getBalance() - amount;
        } else newBalance = balance.getBalance() + amount;
        balanceMapper.update(balance);
        // 落分录 ledger_entry（含 balance_after 快照）          :224-236
    }
    try { transferMapper.insert(record); }                  // :245-246 biz_no PK 幂等兜底
    catch (DuplicateKeyException e) { log.info("转账记录已存在（幂等）..."); }  // :247-248
}
```

TCC 三动作（`freeze:159` / `postAndUnfreeze:179` / `unfreeze:186`）：

```java
public void freeze(ctx) {            // :159 Try：只冻借方非系统户
    for (line : sorted(ctx.getLines())) {
        if (!"D".equals(dir) || isSystem(accountId)) continue;
        BalanceDO b = lockOrCreate(...);
        if (b.getBalance()-b.getFrozen() < amount) throw ...("可用余额不足");
        b.setFrozen(b.getFrozen()+amount); balanceMapper.update(b);
    }
}
public void postAndUnfreeze(ctx) {   // :179 Confirm：★ 先解冻再过账
    unfreeze(ctx);                   // :181 否则全额退款时冻结额≥余额一半会误报可用不足
    post(ctx.getBizNo(), ctx.getLines(), ..., reversed);  // :182
}
public void unfreeze(ctx) {          // :186 Cancel：仅解冻，Math.max(0, frozen-amount) 防御（:196）
    ...
}
```

**为什么这样写**：排序加锁保证全局一致加锁顺序→无死锁；`lockOrCreate`（:294）并发开户竞争捕获 DuplicateKey 后加锁重读（:307-310）。Confirm 先解冻再过账的次序是实测修复的关键——过账的可用余额校验若受本笔冻结影响，全额退款会误报不足。系统户（SYS_ 前缀）可透支不冻。
**流水查询**：`entries(accountId,limit)`（limit≤500）单账户最近分录；`entriesPage(EntryQuery)`（:108-114）跨账户全局分页——page 取 `Math.max(page,1)`、size ≤0 取 20 否则 `Math.min(size,200)`，`EntryMapper.selectPage`（:25）/`countPage`（:28）按 accountId/bizNo/dir/keyword 过滤，accountId 空即查总的（见 §17.2）。

### E.12 参与者装配与路由 — `payment-accounting/.../facade/AccountingTccFacadeImpl.java:15`

```java
@DubboService(group = "accounting")                           // 同接口按 group 区分实现
public class AccountingTccFacadeImpl implements TccParticipantFacade {
    private final TccParticipantTemplate template;            // TccAutoConfiguration（tcc-core）自动装配
    private final AccountingService accountingService;
    public Result<Void> tryAction(TccBranchRequest request) {
        return FacadeSupport.execute(() -> {
            AccountingTccContext ctx = fromJson(request.getContext(), AccountingTccContext.class);
            template.tryAction(request, () -> accountingService.freeze(ctx));   // 模板包裹业务
            return null;
        });
    }
    // confirm → template.confirm(request, () -> postAndUnfreeze(ctx))
    // cancel  → template.cancel(request, () -> unfreeze(ctx))
}
```

协调器侧路由（`payment-payment/.../config/TccParticipants.java:13`）：

```java
@DubboReference(group="accounting") private TccParticipantFacade accounting;
@DubboReference(group="channel")    private TccParticipantFacade channel;
@DubboReference(group="trade")      private TccParticipantFacade trade;
public Map<String,TccParticipantFacade> asMap() { return Map.of("accounting",accounting,"channel",channel,"trade",trade); }
```

**为什么这样写**：参与者只写业务 Runnable，幂等/空回滚/防悬挂全部由模板承担（关注点分离）。`PaymentConfig.java:15-17` 把 `asMap()` 经 `DubboTccParticipant.wrap` 注入 `TccCoordinator`，协调器按分支日志 participant 名路由——新增参与者只需注册 bean + registerBranch，协调器零改动。trade 参与者按 action 分流（`TradeTccFacadeImpl.java:29`：REFUND→checkRefundable/refundMark，PAY→reserve/markPaid/release）。

---

## 附录 F：异常与边界场景全集

> 每行给出：触发条件 → 系统行为 → 恢复/兜底路径 → 验证手段。
> 验证标记：[S]=smoke-test.py 功能断言；[A/B/C/D]=verify-pro.py 并发/混沌/持久化场景；[码]=源码机制保证。

### F.1 重复请求类（幂等防线）

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 1 | 同 requestId 串行重放 | 客户端重试 pay | 预检 selectByRequestId 命中 → 返回原结果 repeated=true | request_id UNIQUE | [S] |
| 2 | 同 requestId 并发 | 8 线程同 requestId | 唯一键竞争，1 胜者执行业务，7 败者 DuplicateKey 回读胜者结果 | insert-first | [A] |
| 3 | 订单已 PAID 后回放 | 支付成功后同 requestId 再请求 | 预检先于状态检查 → 仍返回原始结果（非 409） | 预检顺序 | [S] |
| 4 | 同订单不同 requestId 并发 | 8 线程抢同一订单 | 订单 reserve CAS 仅 1 个 CREATED→PAYING 成功，其余 Try 失败 → TCC 整体回滚 FAILED，余额只扣一次 | trade_order CAS | [B] |
| 5 | 充值重复 bizNo | 同 bizNo 多次 topup | IdempotentTemplate 命中既有结果 | (TOPUP,bizNo) | [S] |
| 6 | 结算/通知消息重投 | outbox 至少一次 | 接收端 IdempotentTemplate 去重，无副作用 | (SETTLE_*,bizId)/(NOTIFY,event:bizId) | [码] |
| 7 | 退款标记重投 | REFUND_MARK 重复 | IdempotentTemplate 命中 | (REFUND_MARK,bizNo) | [码] |
| 8 | 过账兜底重复 | 上游幂等全失效 | transfer_record biz_no PK 冲突 → 记日志跳过 | biz_no PK | [码] |

### F.2 TCC 三大经典问题

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 9 | 分支幂等 | 补偿重试/网络重发 Confirm | tcc_branch_log 主键冲突或 status=CONFIRMED → 直接返回 | (xid,branch_id,action) PK | [C][码] |
| 10 | 空回滚 | Try 超时/丢失，Cancel 先到 | 查无日志 → 落 CANCELLED 标记，不动业务，返回成功 | cancel:80 | [码] |
| 11 | 防悬挂 | 被空回滚后迟到 Try | INSERT 冲突且既有 CANCELLED → 抛 CONFLICT 拒绝预留 | tryAction:43 | [码] |
| 12 | Try 业务失败留痕 | freeze/preauth 抛异常 | 同事务 DELETE 日志 → 后续 Cancel 走空回滚，不误解冻 | tryAction:52 | [S] |
| 13 | 已回滚分支被 Confirm | 状态错乱 | status=CANCELLED → 抛"已回滚分支不可提交" | confirm:69 | [码] |
| 14 | 已提交分支被 Cancel | 状态错乱 | status=CONFIRMED → 抛"已提交分支不可回滚" | cancel:88 | [码] |
| 15 | 凭空 Confirm | 无 Try 记录直接 Confirm | status=null → 抛"无 Try 记录" | confirm:63 | [码] |

### F.3 崩溃与宕机类（持久化恢复）

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 16 | 协调器一阶段崩溃（部分 Try） | Try 执行中宕机 | recover 见 TRYING：非全 TRY_OK → 回滚 | 分支日志持久化 | [D][码] |
| 17 | 协调器一阶段崩溃（全 Try 成功） | Try 全成功未及 commit | recover 见 TRYING：全 TRY_OK → 提交 | recover:114 | [D][码] |
| 18 | Confirm 部分成功崩溃 | 部分分支 CONFIRMED | recover 续驱，跳过 CONFIRMED 分支 | driveConfirm:138 | [C] |
| 19 | 终态事务未提交崩溃 | Confirm 成功但本地事务未落 | 参与者幂等跳过，终态事务重做 | 幂等+本地事务原子 | [码] |
| 20 | 参与者宕机（渠道进程被杀） | CARD 支付 Confirm RPC 失败 | CONFIRMING 挂起，支付单 PROCESSING；渠道恢复后 recover 续驱收敛 SUCCESS | 退避重试 | [C] |
| 21 | 全停重启 | stop.sh→run.sh | local：H2 落盘 + schema IF NOT EXISTS 幂等建表；docker：MySQL pp_* 持久化（schema-mysql.sql 已建，sql.init.mode=never 不重跑）；在途事务/outbox 恢复，快照一致 | 数据库持久化 | [D] |
| 22 | 终态后 outbox 未投递 | 提交后调度前宕机 | outbox 与终态同事务已落库，调度续投绝不丢 | 事务性 outbox | [D][码] |

### F.4 资金与业务边界

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 23 | 余额不足 | 付款户可用 < amount | freeze Try 抛 B0001 → TCC 回滚 → FAILED | 可用余额校验 | [S] |
| 24 | 渠道拒绝 | 卡号尾号 0000 | preauth Try 抛"渠道拒绝" → 账务 Cancel 解冻、渠道/交易空回滚 → FAILED，订单仍 CREATED 可重付 | Try 失败回滚 | [S] |
| 25 | 风控超限 | amount > 10 万元 | 支付单落 FAILED 留痕 + 抛 B0001 | 风控前置 | [S] |
| 26 | 风控限频 | 同 payerId 10s 内 >5 笔 | pass=false → FAILED | 滑动窗口 | [S] |
| 27 | 账户/商户冻结 | status=FROZEN | 下单/支付/过账三处重复校验拒绝 | 纵深防御 | [S] |
| 28 | 超退 | refund amount > 可退余额 | 支付核心 + 交易域双重校验抛 B0001 | 累计额校验 | [S] |
| 29 | 全额退款手续费闭环 | 最后一笔退款 | feeRefund = fee − refundedFee（退剩余全部），无一分残留 | 比例退+末笔兜底 | [S] |
| 30 | 全额退款可用余额误报 | 冻结额≥余额一半 | Confirm 先解冻再过账，校验不受本笔冻结影响 | postAndUnfreeze 次序 | [S] |
| 31 | 退款乱序消息 | REFUND 先于 PAY 到 settle | recordRefund 找不到原 PAY → 投递失败进退避，天然等 PAY 先达 | 退避重试 | [码] |
| 32 | 未扣款退款 | 原渠道交易非 CAPTURED | preauth(REFUND) 校验原交易抛 B0001 | 渠道校验 | [码] |
| 33 | 并发开户竞争 | 首笔业务懒开户 | lockOrCreate INSERT 竞争捕获 DuplicateKey 后加锁重读 | lockOrCreate:294 | [码] |
| 34 | 多账户转账死锁 | 并发交叉转账 | 按 accountId 排序加锁，全局一致顺序 → 无死锁 | sorted:318 | [码] |

### F.5 异步投递与通知边界

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 35 | outbox 投递失败 | settle/notify 不可达 | attempts+1 退避 {3,6,12,24,48,96,192,384}s | 退避重试 | [码] |
| 36 | outbox 重试耗尽 | attempts≥8 | 置 DEAD + error 日志，对账"支付-清结算一致"报缺失笔数 | DEAD+对账 | [码] |
| 37 | 通知死链 | callbackUrl 不可达 | PENDING 退避 {5,10,20,40,80}s，attempts≥5 → FAILED（记录永久可查，商户可 PULL 补查） | NotifyPusher | [S] |
| 38 | 无 callbackUrl | 商户未配回调 | 登记即 PULL 模式，不推送 | PULL | [S] |
| 39 | TCC 补偿耗尽 | retry≥8 | 置 DEAD + error，待人工/对账介入，不无限重试 | bumpRetryIfPending:196-197 | [码] |

### F.6 接入与安全边界

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 40 | OpenAPI 错签 | sign 不匹配 | TreeMap 排序拼接 HmacSHA256 比对失败 → A0400 | HMAC 验签 | [S] |
| 41 | OpenAPI 过期时间戳 | 时差 > ±300s | A0400"timestamp 超出 5 分钟有效期"（防重放） | 时间窗 | [S] |
| 42 | 下游不可达 | Dubbo RpcException | 网关 GlobalExceptionHandler → 503 C0001，不泄漏堆栈 | 异常映射 | [S] |
| 43 | 非法 JSON / 未知路由 | 请求体损坏/路径不存在 | 400 / 404，统一错误体 | 异常映射 | [S] |
| 44 | 卡号敏感信息 | 查询支付单 | 出参脱敏 622202****0202，库内全卡号供渠道 | maskCard | [S] |

### F.7 对账故障隔离

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 45 | 某域不可达 | recon 拉取失败 | 逐项故障隔离：仅该项判失败并记原因，不影响其余五项 | detailAction 隔离 | [码] |
| 46 | 在途单误报 | PROCESSING 支付单 | 不参与核对（只采 SUCCESS），避免补偿收敛前误报 | 采集过滤 | [C] |
| 47 | 明细与判定不一致 | 计数与明细同源 | failSignal 读 detailAction 计数判 pass，杜绝"明细齐备却判失败" | 同源计数 | [码] |

### F.8 设计不变量（任意时刻成立）

```
Σ借方 = Σ贷方                       （试算平衡，validateLines 强校验）
Σ所有账户余额 = 0                    （封闭系统守恒；冻结期间余额未动亦成立）
SYS_FEE 余额 = Σ结算净手续费          （手续费闭环）
商户余额增量 = Σ结算净额              （结算闭环）
同一 requestId → 同一响应            （幂等语义，与当前业务状态无关）
未预留资源 = 无分支日志              （Try 失败删日志，空回滚安全）
全局终态 + 支付单翻转 + outbox 原子   （事务性 outbox，无中间态）
```

### F.9 Docker 中间件与资金单边界（v1.7/v1.8，docker profile）

> 中间件可插拔：MQ/Redis/ZK 故障均不破坏资金正确性，最坏退化为 local 等价路径或 fail-fast。验证标记 [码]=源码机制保证；docker 回归 smoke 79/79 + verify 22/22 + MQ Consume Diff=0。

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 48 | MQ 主投递发送失败 | broker 宕机/网络断 | `RocketMqOutboxPublisher.publish` 吞异常（:35-37，事务已提交不能抛），outbox 行留 PENDING | 宽限期后轮询兜底投递 | [码] |
| 49 | MQ 消费失败/重投 | 消费抛错或 broker 重投 | `consumeMessage` 返回 RECONSUME_LATER（:40）；`deliverByEventBizId` 的 `!PENDING` 守卫（:75）+ 接收端 IdempotentTemplate 去重 | 至少一次→恰好一次 | [码] |
| 50 | MQ 整体不可用 | namesrv/broker 全停 | 主投递静默失败，退化为纯 outbox 轮询（3s），可靠性语义不变 | outbox 轮询兜底 | [码] |
| 51 | Redis 限流不可用 | Redis 宕机 | `RedisRateLimiter.tryAcquire` 抛异常 → RiskService 不捕获 → FacadeSupport 映射 **B0002**（:33-35），支付受理 fail-fast | **不降级内存、不静默放行**（与商户缓存策略相反） | [码] |
| 52 | Redis 商户缓存不可用 | Redis 宕机/读写异常 | `MerchantCache.get` 读异常回源 Dubbo（:48-50）、写异常吞掉（:55-57）、未启用/无 bean 直查 | 验签链路不中断，优雅降级 | [码] |
| 53 | MySQL 建表缺失 | 空库未跑 schema-mysql.sql | `sql.init.mode=never` 不自动建表，启动/首笔 SQL 报错 | 部署时对空库一次性执行 schema-mysql.sql（见 §18.2/§18.6） | [码] |
| 54 | Docker ZK 不可用 | 共享 ZK 2181 未起 | `run.sh --docker` /dev/tcp 探测失败提示先起栈；`HealthService.registryUp` docker 走 TCP `ruok`→`imok` 探活（800ms） | Dubbo 注册/发现依赖 ZK，须先起中间件 | [码] |
| 55 | 提现余额不足 | fundPay WITHDRAW 且 balance<amount | 受理前置校验抛 B0001（:180-182），先于 createOrder/TCC，不留 PROCESSING 单 | 前置校验 + TCC freeze 双重 | [S] |
| 56 | 资金单重复 requestId | 同 requestId 重试 fund.topup/withdraw | `selectByRequestId` 回放（:168）+ outTradeNo=requestId 订单幂等（:187）+ insert DuplicateKey 回读（:209） | request_id UNIQUE + 订单 (merchantId,outTradeNo) | [S] |