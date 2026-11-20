# payment-pro 架构设计文档（ARCHITECTURE）

> 版本 2.0.0 · 2026-11-20 · **交付态文档**（描述已运行并验收的架构）
> 文档链：**REQUIREMENTS**（需求与验收）→ **ARCHITECTURE**（本文）→ **DESIGN**（详细设计/字段级 DDL/代码走读）
> 运行态取证：pp **13/13** 健康、账务试算 `D=C=3,330,946,497`、`balanceSum=0`、1355 账户 / 9324 分录。

---

## 1. 架构总览

### 1.1 风格
**微服务 + 领域驱动分域 + 自研分布式事务（XTS 风格 TCC）+ 事务性 outbox 可靠投递**。
同步 RPC 用 Dubbo 3.2.14（ZooKeeper 注册），异步用 RocketMQ 事务消息 + outbox 轮询兜底，配置用 Apollo，调度用 XXL-Job + ShedLock，限流熔断用 Sentinel。

### 1.2 分层
```
业务方 App（ride-pro 乘客端，自渲染收银台）
        │ HTTPS + HMAC-SHA256 签名
        ▼
┌─────────────────────────────────────────────┐
│ payment-gateway :8080 / :8443               │  开放面 + 管理台静态资源
│  OpenApiController · ApiController ·        │  验签/限流/熔断/RBAC/step-up/审计
│  SettleOpsController · PlatformOpsController│
└─────────────────────────────────────────────┘
        │ Dubbo（Result<T> 包装 + orThrow）
        ▼
┌──────────────────────────────────────────────────────────────┐
│ 业务域（各自独立进程 + 独立库）                                │
│ member 20881 · account 20882 · accounting 20883 · trade 20884 │
│ payment 20885 · cashier 20886 · channel 20887 · risk 20888    │
│ settle 20889 · notify 20890 · recon 20891 · marketing 20893*  │
└──────────────────────────────────────────────────────────────┘
        │                         │
        ▼                         ▼
┌────────────────┐      ┌──────────────────────────┐
│ tcc-core       │      │ 中间件（docker profile）  │
│ 协调器 dtx_*   │      │ MySQL pp_* · ZK 2181     │
│ 参与者 SPI     │      │ RocketMQ · Redis · Apollo│
└────────────────┘      └──────────────────────────┘
```
\* marketing 为可选第 14 进程（开关启用，不计入 13/13 门禁）。

### 1.3 Maven 模块（17）
| 模块 | 职责 |
|---|---|
| `tcc-core` | 传输无关 TCC 组件：`TccCoordinator` / `TccParticipant` SPI / `TccParticipantTemplate` / `IdempotentTemplate` / `TccSchema` / `TccAutoConfiguration` |
| `payment-common` | 结果与错误码、异常、`MetricsSupport`、`ShardTableBootstrap`、`ShardRowMirror`、job 基础设施（`JobAutoConfiguration`/`JobControlEndpoint`/ShedLock）、DynamicConfig |
| `payment-facade` | 全部对外 Dubbo 契约 + DTO（跨域唯一依赖面） |
| `payment-registry` | local profile 内嵌 ZooKeeper |
| `payment-member` … `payment-marketing` | 12 个业务域 |
| `payment-gateway` | REST/OpenAPI/管理台静态资源/安全过滤器 |

**依赖纪律**：业务域之间**只经 facade 契约**通信，不得直接依赖对方实现；新增 facade 方法必须全 reactor 可编译。

---

## 2. 进程与端口（运行态实测）

| 服务 | Dubbo/HTTP 端口 | actuator | 域 |
|---|---|---|---|
| gateway | 8080（HTTP）/ 8443（HTTPS） | 18080 | 开放网关 |
| registry | 2181 | — | ZooKeeper（local 内嵌 / docker 容器） |
| member | 20881 | 18081 | 会员域 |
| account | 20882 | 18082 | 账户域 |
| accounting | 20883 | 18083 | 账务域 |
| trade | 20884 | 18084 | 交易域 |
| payment | 20885 | 18085 | 支付核心 |
| cashier | 20886 | 18086 | 收银台 |
| channel | 20887 | 18087 | 渠道网关 |
| risk | 20888 | 18088 | 风控域 |
| settle | 20889 | 18089 | 清结算域 |
| notify | 20890 | 18090 | 消息通知 |
| recon | 20891 | 18091 | 对账域 |
| marketing（可选） | 20893 | 18093 | 营销域 |

**端口隔离**：ride-pro 占 8090 系；local 与 docker 共存须隔离 ZK（2183/8193）；XXL-Job executor 9994–9998 已满（claim 用 9999）；Apollo 18070–18072。

---

## 3. 分布式事务架构（tcc-core）

### 3.1 为什么自研而非 Seata
已拍板保留自研。理由：与账务复式记账/幂等体系深度耦合、传输无关 SPI 便于 Dubbo 桥接、可控的崩溃恢复矩阵与重试预算。

### 3.2 全局状态机
`TRYING → WAITING → CONFIRMING → CONFIRMED`
`TRYING/WAITING → CANCELLING → CANCELLED`
异常终态 `ERROR`；`DEAD` 表示重试耗尽转人工。

### 3.3 关键护栏（**均为实测缺陷修复后固化**）
| 护栏 | 说明 |
|---|---|
| **互斥 CAS 状态推进** | `casGlobalIn(xid, to, reason, allowedFrom...)`：`UPDATE ... WHERE xid=? AND status IN (...)`，返回是否恰 1 行。`commitAsync` 只从 TRYING/WAITING/CONFIRMING 进 CONFIRMING；`toCancelling` 只从 TRYING/WAITING/CANCELLING 进 CANCELLING。**杜绝 confirm/cancel 竞态双驱** |
| **驱动入口终态守卫** | `driveConfirm`/`driveCancel` 入口校验当前状态：已 CONFIRMED/CANCELLED → 幂等返回；其它非法态 → ERROR 日志 + 返回 |
| **一阶段宽限** | begin 后写 `next_retry_at = now + 10s`，避免恢复任务过早驱动 |
| **让位语义** | CAS 失败即「让位」，日志留痕后返回，不重复驱动 |
| **参与者分支幂等** | `tcc_branch_log` 主键 + `IdempotentTemplate` |
| **空回滚/悬挂防护** | Cancel 先于 Try 到达时留痕，Try 后到即让位 |

### 3.4 参与者桥接
`DubboTccParticipant` 把线上契约 `TccParticipantFacade`（`Result` + `orThrow`）桥接为组件 SPI `TccParticipant`（直接抛异常）；`BizException` → `TccException` 保留原错误码。
参与者按 Dubbo **group** 区分同接口不同实现（accounting / channel / trade）。

> **I-6 待实现**：当前 `TccParticipants` 硬编码 3 个 `@DubboReference` + `Map.of`，新增参与者域须改该类。目标改为按 group 从注册中心自动发现（自注册）。

### 3.5 失败出口全覆盖（**零悬挂不变量**）
状态机每个失败出口都必须回滚/重开，含 TCC 回滚路径：
- 受理前失败（xid==null）→ 支付单 FAILED 留痕 + 腿终态 + 承载单关闭 + **合并单重开**
- TCC 回滚（`onCancelled`）→ 支付单 FAILED + 腿 FAILED + 营销退回 + **合并单重开**（afterCommit 执行 RPC，CAS 幂等，失败由下次 payMerge 的 PAYING 续驱兜底）
- **守卫 try 起点必须紧跟「置中间态」之后，而非事务开始之后**
- `reopenMerge` 的「子订单已释放」判定须接受 **CLOSED 终态**（到期关闭是合法终态，不阻塞重开）

---

## 4. 数据架构

### 4.1 双 profile
| profile | 存储 | 注册 | 消息 | 缓存 | 配置 |
|---|---|---|---|---|---|
| **local**（零依赖） | H2 文件（`data/`） | 内嵌 ZK | outbox 轮询 | 内存 | 本地默认值 |
| **docker** | MySQL `pp_*` 多库 | 容器 ZK 2181 | RocketMQ + outbox 兜底 | Redis | Apollo |

启动：`./run.sh`（local）/ `./run.sh --docker --no-build`；停止 `./stop.sh`（**保留 data/**，清残留进程 + 等端口释放）。

### 4.2 分库分表（ShardingSphere-JDBC 5.5.0）
**单库分片**（不分库），分片键：
| 表 | 分片键 | 片数 |
|---|---|---|
| `settle_record` | merchant_id CRC32 % 4 | 4 |
| `recon_report` | id | 4 |
| `payment` | id | 4 |
| `refund_record` | payment_id | 4 |
| `account_balance` / `ledger_entry` | account_id（同账户同片） | 4 |
| `trade_order` | merchant_id | 4 |

**全 SINGLE（不分片）**：`dtx_transaction` / `dtx_branch` / `outbox` / `idempotent_record` / `sequences` / `shedlock` / `amount_review` / `transfer_record` + 全部独立幂等索引表。

**ID 形态不变**（`P/R/O/E/RV/SP/MG + 数字`）以保 ride 存量兼容 —— 因此**拒绝基因法**，改用独立索引表（`payment_request_index` / `refund_request_index`，先占位再写业务单，同一本地事务）。

**当前态**：双写开、`initial-active=false`（**单库观察态**），切流须拍板。`shard_route_state` 持久化防重启方向反转；`ppshard` toggle 双向回滚逐条一致（含序），差异非 0 拒绝切换。

### 4.3 影子双跑
`ShardRowMirror` 对可变行做**整行真值 upsert**，`shadowCompare` 逐行逐值比对，`diffTotal=0`。
`ShardTableBootstrap.alignColumns` 双向比对原单表与分片表列集，只 `ADD COLUMN`（nullable + 源默认值，跳过 AUTO_INCREMENT），**绝不 DROP/MODIFY**；bootstrap 带**有界重试**（3 次，3s/6s 退避）应对共享 MySQL 瞬时 stall。

### 4.4 账务模型
- 复式记账：每笔业务借贷必相等
- `balance = credit − debit`；`available = balance − frozen`
- 客户户严禁透支（SQL 守卫 `balance - frozen_amount >= amount`）；系统户（SYS_CASH/SYS_FEE/SYS_DEPOSIT/SYS_ADVANCE）允许透支
- **排序加锁防死锁**（按 account_id 排序）
- 科目：1001 外部现金 / 2001 客户余额 / 2006 保证金 / 6001 手续费收入 / 2999 待映射（SYS_ADVANCE 暂落，申请新增 1002 垫资应收款）
- 不变量：试算 `D=C`、`Σbalance=0`、`balance(SYS_DEPOSIT)=Σ缴存−Σ释放−Σ扣划`、`used=Σ垫资−Σ追偿=−balance(SYS_ADVANCE)`

---

## 5. 可靠消息架构

### 5.1 outbox 是唯一事实来源
业务本地事务内写 outbox 行 → 投递。**RocketMQ 事务消息为主投递，3s 轮询兜底永不删除**。

### 5.2 事务消息
半消息 + 本地事务判定 + 回查：
- DB 异常返回 **UNKNOWN**，绝不臆断 ROLLBACK
- 回查只写 additive 元数据（`topic` / `msg_id` / `half_state`）
- 多 topic 路由（`TopicRouter`）

### 5.3 事件面
`SETTLE_PAY` / `NOTIFY_PAY` / `SETTLE_REFUND` / `NOTIFY_REFUND` 等；notify 域签名 + 重试 + DEAD + 人工重放。

### 5.4 补单收敛
`PaymentReconcileService` / `RefundReconcileService`：
- 触发：超时在途扫描 + 手动/回执后即时收敛
- 决策表按 `dtx 状态 × allTryOk × 渠道终态` → RESUME_COMMIT / RESUME_CANCEL / DRIVE / WAIT / MANUAL / NOOP
- **渠道状态未知只等待**；DEAD/查询耗尽只告警转人工，**绝不臆断失败**
- 无 dtx 记录（begin 前崩溃）→ CAS 置 FAILED

---

## 6. 幂等体系

| 层 | 机制 |
|---|---|
| 商户侧 | `merchant + outTradeNo` 唯一键（交易单） |
| 请求侧 | `requestId` 全局唯一 + `idempotent_record` 占位/结果回填 |
| 事务侧 | `xid` + `tcc_branch_log` 主键 |
| 账务侧 | 确定性 `bizNo` 派生（`P/R/O/E/RV/CX/DP:/DR:/DD:/AD:/AR:` + `refundId:legSeq`） |
| 状态推进 | **CAS 行数检查**（`WHERE status='期望前态'`，返回必须恰 1 行，否则让位） |
| 分片侧 | 独立索引表先占位（单库不分片，与业务单同一本地事务） |

**MySQL 陷阱（已固化）**：`UPDATE` 赋值**从左到右**求值。`SET original_amount=COALESCE(original_amount, amount), amount=#{new}` 顺序不可颠倒，否则留痕取到新值。

---

## 7. 安全架构

### 7.1 开放面
HMAC-SHA256：`appId` / `sign` / `timestamp`（**毫秒**）/ TreeMap 排序 `k=v&` / ±300s 窗口 / **nonce 可选**（ride 不传，走既有幂等）。
验签通过后 → appId 热点参数 QPS 限流（防伪造 appId 耗他人配额）→ 下游熔断。
拒绝一律发生在**受理前**，不落单、不留悬挂。错误体规范：`A0429`（限流）/ `B0003`（降级）/ `C0001`（下游不可达）。

### 7.2 管理台
Spring Security Bearer **STATELESS** + SessionStore（docker Redis / local 内存）+ 登录限流（5 次失败 → 429）。
RBAC 四角色：ADMIN(`*`) / OPERATOR / AUDITOR / READONLY，权限点细到模块读写。
**二次认证 step-up**：`POST /api/security/step-up` 经 `AuthFacade.authenticate` **再验当前会话用户自己的口令**，operator 恒取会话用户名，声称他人 → 403，令牌 300s，请求头 `X-Step-Up-Token`。
**双人复核**：submitter/approver 恒取会话用户，body 冒充 → 403；大额触发 `A0428 + reviewId`，审批后才动账。
闸门覆盖：复核审批、账务调整/准备金、密钥轮换、差错审批、**autofix 仅 execute=true**（影子模式不拦）、通知重发、渠道限额/维护窗/对账、结算周期/分账。
`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、`Cache-Control: no-store`。

### 7.3 密钥与敏感数据
appSecret 仅注册响应返回一次；口令/密钥绝不落库明文、绝不回显。
敏感配置放仓库外 `~/.config/payment-pro/`（600），仓库只留 example。
Vault 缺失时自动降级本地文件实现（零依赖），**生产须接真 Vault**。
卡号/身份证/手机号脱敏；C 端认证只存一次性 verifyToken，绝不存支付口令/短信码。

---

## 8. 治理与可观测

| 组件 | 用途 | 降级 |
|---|---|---|
| **Apollo** | 4 namespace 配置热更（fee bps / 风控限额 / 开关） | **停配置中心支付仍成功**，热更不重启生效；默认值口径逐一不变 |
| **XXL-Job** | 调度托管，3 executor 真注册 | 调度中心不可达 → 本地 `@Scheduled` 兜底接管（告警留痕） |
| **ShedLock** | 跨实例互斥 + 孤儿锁回收 | 三域锁表 |
| **Sentinel** | URL 级 QPS 总闸 + appId 热点参数 + 异常数熔断 | Dubbo 消费端 fallback → B0003/A0429 规范错误体 |
| **Micrometer/Prometheus** | 指标 + 告警判定 | actuator 暴露 |

**条件装配陷阱（已固化）**：`JobAutoConfiguration` 是 `@ConditionalOnBean(JobHandler.class)`。只有裸 `@Scheduled` 而无 JobHandler bean 的域（如 member），整个 job 装配含 `/actuator/ppjob` 端点被跳过 → 404 是**真实语义**，管理台须如实说明而非报故障。

**长耗时 ops 超时**：通用 Dubbo 8s 超时会把服务端已正常跑完的长批次判成 C0001/503。须用**方法级** `@Method(name="runSettleBatch", timeout=120000)`，不整体放宽。

---

## 9. 跨系统架构（ride-pro 双账本）

### 9.1 集成形态
ride-pro 经 `PaymentProClient`（TreeMap + HmacSHA256）调 pp OpenAPI；本地钱包与 pp 账户**双账本镜像**。
ride 侧 `mirror_outbox` → `MirrorExecutor` 同步调用 pp → 回执确认本地动账；崩溃恢复由 `MirrorDispatcher` 续传。

### 9.2 冻结面与扩展面
**v1 冻结 8 方法**：`trade.create/pay/query` · `account.open/balance/transfer` · `fund.topup/withdraw`（逐字节不变，md5 + 方法集双证）。
**additive 11 新方法**：9 个 `cashier.*` + `channel.sign` + `channel.agent.pay`。
门禁：`ride-pro/verify-integration.py` **24/24**（含停 pp 重启混沌 + pp 六项对账 pass），已通过 6 次。

### 9.3 双账本核对口径（**实测缺陷修复后固化**）
```
pp 余额 == 开户基线快照 + 已确认净额
已确认净额 = Σ(该户全部 CONFIRMED 流水)   ← 不是「已投递镜像的腿」
平台户另减 Σpp 通道费（通道费属支付系统营收）
```
| 要点 | 原因 |
|---|---|
| 净额用**全量 CONFIRMED 腿** | 历史冲正是本地更正不镜像 pp，只统计镜像腿会让冲正对监控不可见 |
| 在途判定按**全部借贷腿的用户** | 一笔 PAY 同时动司机与平台的钱；只按镜像行 userId（乘客）判在途，会让司机/平台基线在净额低估时被回填 → **快照吸收在途金额后永久重复计算** |
| 回填基线要求**全局静默** | 同上 |
| 通道费**缓存 + 节流** | 每轮逐条远程查对端会撞 Sentinel 热点限流（50 QPS/appId）；缓存不变量 + 每轮 20 条 + 60ms 间隔；`feesComplete=false` 时平台户核对与基线重算**主动跳过** |
| **拿到回执号 ≠ 钱已划转** | pp 受理是「同步受理→PROCESSING→SUCCESS」；须 `requireSettled` 校验终态：SUCCESS 才确认，FAILED/CLOSED 走拒绝退回，其余保持 PENDING 续传 |
| **CARD 不扣用户钱包** | pp CARD 单钱来自 SYS_CASH，不扣 BALANCE 户；业务方须用外部清算户出资 |
| 共享商户户**按单比** | 商户号被平台自测流量共用，余额不可直接比 |

### 9.4 收银台开放面
平台不建 C 端页面。`cashier.*` 开放给 ride 自渲染，视觉对标支付宝（`#1677FF` / 6 格密码框 / 吸底胶囊按钮 / 绿勾）。
主扫 `qrContent` 是 URL，消费方按 `code=` 归一化；被扫等于码值。

---

## 10. 关键不变量清单（架构级红线）

1. 借贷必相等；`Σbalance=0`；试算 `D=C`
2. 客户户严禁透支；系统户可透支
3. **零悬挂**：任何失败出口都回滚/重开，含 TCC 回滚；守卫 try 起点紧跟置中间态
4. outbox 唯一事实来源；轮询兜底永不删除；DB 异常返回 UNKNOWN
5. 拿到回执号 ≠ 钱已划转，须校验对端终态
6. 同一语义判定两侧**共用谓词**（如 `isComboMode`），不得一侧硬编码
7. 状态推进一律 CAS + 行数检查，失败即让位
8. 分片只 CREATE+INSERT 复制、原行保留、不下线单库；ID 形态不变
9. 历史差错只走红冲 + 双人 + 审计，**不改不删原行**
10. 限流/容量拒绝在受理前；业务失败须留 FAILED 痕
11. 敏感操作二次认证 + 大额双人复核，operator 恒取会话用户
12. v1 冻结面逐字节不变，新增一律 additive
13. 周期对账不得每轮逐条远程查对端
14. 长耗时 ops 用方法级超时
15. 一致性保证放服务端，前端补偿只管展示

---

## 11. 部署拓扑与演进

### 11.1 当前部署
单机 docker 共享中间件栈（`claim-shared-*`：MySQL 3306 / ZK 2181 / RocketMQ 9876 / Redis 6379），pp 13 进程 + ride 9 进程 + 可选 marketing。

### 11.2 生产必改（见 REQUIREMENTS §6）
ZK 集群化（I-1 批准不做，列入必改）、中间件隔离、正式 CA 证书、真 Vault、默认口令、沙箱必关、配额计费、切流拍板。

### 11.3 演进方向
- I-6 参与者自注册（按 Dubbo group 从注册中心发现，去掉硬编码）
- 分片切流（当前单库观察态）
- 科目 1002 垫资应收款 / 6004 保证金扣划收入 正式映射
- 网银渠道 CH-5、授信账户、快捷支付签约（当前如实标注「预留」，不假实现）