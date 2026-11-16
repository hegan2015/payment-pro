# payment-pro 详细设计文档

> 参考支付宝领域划分的工业级支付系统
> Spring Boot 3.2 + Apache Dubbo 3.2 + 自研 XTS 风格 TCC + 事务性 Outbox
> 版本：1.1.0 ｜ 文档日期：2026-11-16
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
| 开箱即用 | H2 嵌入式文件库 + 内嵌 ZooKeeper，`./run.sh` 一键编译启动 13 进程 |

### 1.2 关键取舍（为什么这样设计）

**为什么自研 TCC 而不是 Seata？**
- 约束是零外部依赖：Seata 需要独立部署 TC Server，违背"一键运行"。
- 支付场景天然适合 TCC：资金预留（冻结）、渠道预授权、订单预留都是标准的"资源预留"语义，比 AT 模式的全局锁更贴合支付业务，且无脏写问题。
- 自研可以完整展示 XTS 的核心机制（事务日志、分支日志、空回滚、防悬挂、补偿恢复），这些机制与蚂蚁 XTS / Seata TCC 的设计同构，代码即文档。

**为什么用事务性 outbox 而不是 MQ？**
- RocketMQ/Kafka 同样需要外部部署。outbox 模式把"业务终态 + 待发消息"放进同一个本地事务，原子性由数据库保证，投递由调度器补偿——这是分布式消息的最终一致性标准解法之一，正确性与 MQ 事务消息等价。
- 语义：**至少一次投递 + 接收端幂等 = 恰好一次效果**。

**为什么 H2 文件库 + 每域一库？**
- H2 文件模式提供真实的事务、行锁（SELECT FOR UPDATE）、唯一约束、序列——TCC 与幂等的所有正确性论证都依赖这些数据库能力，内存 Map 做不到。
- 每域独立数据库文件（`data/<domain>.mv.db`）模拟"数据库按域拆分"的微服务数据自治原则：跨域绝不共享表，只通过 Dubbo 接口访问，为将来平滑迁移 MySQL 分库留好边界。

**为什么一阶段同步、二阶段异步？**
- Try 同步执行：失败立即整体回滚并给用户明确错误（余额不足、渠道拒绝等），用户体验与资金安全都要求 Try 阶段强一致反馈。
- Confirm/Cancel 异步驱动：二阶段只操作"已预留"的资源，理论上必然成功，失败只是暂时性的（网络/宕机），交给补偿调度按退避重试，接口快速返回 PROCESSING，客户端轮询终态。这是 XTS 的标准形态。

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

### 2.2 域职责一览

| 域 | 进程 | Dubbo 端口 | 职责 | 数据库 |
|---|---|---|---|---|
| 网关 | payment-gateway | 20880 | HTTP 入口、OpenAPI 验签、聚合查询、静态控制台 | 无 |
| 注册中心 | payment-registry | — | 内嵌 ZooKeeper（2181）+ 健康探针（8092） | 无 |
| 会员 | payment-member | 20881 | 用户/商户注册、appId/appSecret、冻结 | member |
| 账户 | payment-account | 20882 | 支付账户开立、状态管理（余额权威在账务域） | account |
| 账务 | payment-accounting | 20883 | 复式记账、余额/冻结、充值、试算平衡 | accounting |
| 交易 | payment-trade | 20884 | 订单生命周期状态机（CAS） | trade |
| 支付核心 | payment-payment | 20885 | 支付/退款编排、TCC 协调器、outbox | payment |
| 收银台 | payment-cashier | 20886 | 支付方式渲染、支付受理转发 | 无 |
| 渠道 | payment-channel | 20887 | 模拟银行：预授权/扣款/撤销/退款 | channel |
| 风控 | payment-risk | 20888 | 单笔限额、滑动窗口频率 | 无（内存） |
| 清结算 | payment-settle | 20889 | 结算单登记、商户汇总 | settle |
| 消息 | payment-notify | 20890 | 商户通知登记、HTTP 推送、退避重试 | notify |
| 对账 | payment-recon | 20891 | 五方核对、报告落库 | recon |

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
├── payment-common      基础库：Result/ResultCode/BizException/MoneyUtil/TraceFilter
├── payment-facade      契约库：全部 Dubbo 接口 + DTO + TCC 上下文 + HealthFacade(Impl)
├── payment-dtx         分布式事务库：TccCoordinator/TccParticipantTemplate/IdempotentTemplate
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

### 3.2 依赖规则

```
业务域 ──▶ payment-facade ──▶ payment-common
业务域 ──▶ payment-dtx ─────▶ payment-facade / payment-common
```

- **facade 只含接口与 DTO**，消费方只依赖契约，不依赖实现（Dubbo 的标准工程实践）。
- **dtx 是独立可复用库**：协调器/参与者模板/幂等模板不绑定任何业务域，通过 `DtxAutoConfiguration` 自动装配，任何域引入即可获得 TCC 与幂等能力。
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
  consumer:
    check: false        # 启动不强制依赖在线，支持任意启动顺序
    timeout: 8000
    retries: 0          # 关键：写操作绝不自动重试，重试权交给业务幂等层
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
- 网关 `HealthService` 聚合：gateway 自身 + registry（HTTP 探针 8092/health，800ms 超时）+ 11 域 Dubbo ping（timeout 1500ms），任一失败标红但不影响其余。
- `run.sh` 启动后轮询聚合健康直至 13/13 才宣告就绪。

---

## 5. 数据架构

### 5.1 存储选型

- H2 2.x **文件模式**，每域一个库：`jdbc:h2:file:${DATA_DIR:./data}/<domain>;MODE=MySQL;DATABASE_TO_LOWER=TRUE`
  - `MODE=MySQL`：SQL 方言贴近生产 MySQL，迁移成本低。
  - `DATABASE_TO_LOWER=TRUE`：标识符小写，与 MyBatis 驼峰映射（`map-underscore-to-camel-case`）配合。
  - 注意：H2 2.x 要求相对路径显式 `./` 前缀（隐式相对路径报 90011）。
- 建表：各域 `schema.sql`（`CREATE TABLE IF NOT EXISTS` + `CREATE SEQUENCE IF NOT EXISTS`），Spring SQL init 幂等执行，重启不丢数据。
- ID 生成：数据库序列（`SELECT NEXT VALUE FOR seq_xxx`）+ 业务前缀（P 支付 / R 退款 / O 订单 / N 通知 / S 结算 / RC 对账 / E 分录 / T 全局事务），全局可读、无中心发号器依赖。

### 5.2 支付核心库表（payment）

```sql
payment            -- 支付单：request_id UNIQUE（幂等键）、xid（关联全局事务）、
                   -- status PROCESSING/SUCCESS/FAILED/REFUNDED、refunded_amount/refunded_fee
refund_record      -- 退款单：request_id UNIQUE、xid、biz_no（账务转账号）
outbox             -- 本地消息表：event/biz_id/payload/status(PENDING/DELIVERED/DEAD)/
                   -- attempts/next_retry_at/last_error；索引 (status,next_retry_at)
dtx_transaction    -- 全局事务日志：xid PK、(biz_type,biz_no) UNIQUE、status、
                   -- retry_count/next_retry_at/version；索引 (status,next_retry_at)
dtx_branch         -- 分支日志：(xid,branch_id) UNIQUE、participant/action/status/context/seq
```

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
  TccCoordinator ── dtx_transaction / dtx_branch（本地库）
       │ Dubbo: TccParticipantFacade.tryAction/confirm/cancel
       ├──▶ accounting 参与者（冻结/过账/解冻）
       ├──▶ channel 参与者（预授权/扣款/撤销）
       └──▶ trade 参与者（订单预留/标记已付/释放）
```

参与者通过 `TccParticipants` 配置注册为 `Map<String, TccParticipantFacade>`（key=participant 名），协调器按分支日志中的名字路由——新增参与者只需注册 bean + registerBranch，协调器零改动。

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

`driveConfirm` 的关键设计（`TccCoordinator.java:140`）：

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

`tcc_branch_log` 主键 `(xid, branch_id, action)`，日志与业务动作在**同一个 REQUIRES_NEW 本地事务**内提交（`TccParticipantTemplate.java`）：

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

用于充值（TOPUP/bizNo）、结算登记（SETTLE_PAY/paymentId、SETTLE_REFUND/bizId）、退款标记（REFUND_MARK/bizNo）、通知登记（NOTIFY/event:bizId）：

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
→ INSERT payment(PROCESSING, request_id UNIQUE)
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

简化规则引擎（内存滑动窗口）：
1. 单笔限额 10,000,000 分（10 万元）；
2. 同一 payerId 10s 内 ≤5 笔（`ConcurrentHashMap<String, Deque<Long>>` + 窗口锁）。

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
2. `memberFacade.getByAppId(appId)` 定位商户 appSecret。
3. 参数按 key TreeMap 排序（剔除 sign、跳过 null）→ `k=v&k=v` 拼接 → **HmacSHA256(secret)** → hex 与 sign 比对，失败 400"签名验证失败"。
4. `trade.create / trade.pay` 映射内部 facade（outTradeNo→requestId，BANKCARD→CARD 归一化）。

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

### 13.3 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| DATA_DIR | ./data | H2 数据目录 |
| FEE_BPS | 60 | 手续费基点（0.6%） |
| ZK_PORT / ZK_HTTP_PORT | 2181 / 8092 | 注册中心 |
| GATEWAY_PORT | 8080 | HTTP 入口 |
| \<NAME\>_DUBBO_PORT | 20880~20891 | 各域 Dubbo 端口 |

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
| 存储 | H2 单机文件库 | 每域替换 MySQL（SQL 已按 MySQL 方言编写，schema 平滑迁移） |
| 注册中心 | 内嵌单点 ZK | ZK 集群 / Nacos |
| 风控窗口 | 单进程内存 | Redis 滑动窗口 / 规则引擎平台 |
| 调度器 | 单实例 @Scheduled | 多实例 + 分布式锁（ShedLock）或分片调度 |
| TCC 参与者路由 | 协调器内静态 Map | 参与者自注册 + 动态发现 |
| DEAD 事务 | 日志+对账暴露，人工介入 | 差错处理平台（自动冲正/挂账） |
| 渠道 | 模拟银行（0000 拒绝规则） | 真实银行/卡组织适配器，渠道侧异步回执+查询补单 |
| 通知安全 | 明文 HTTP POST | 通知体签名（商户验签）+ HTTPS |
| 对账 | 全量实时核对 | 日切批量对账 + 渠道对账文件解析 |
| 认证 | OpenAPI HMAC；控制台无登录 | 控制台 OAuth2/RBAC |

这些边界不影响当前系统的正确性论证：所有资金一致性机制（TCC、幂等、outbox、复式记账、对账）都是生产同构的，替换的只是基础设施载体。

---

## 附录 A：关键代码索引

| 机制 | 位置 |
|---|---|
| TCC 协调器 | payment-dtx/.../TccCoordinator.java |
| TCC 参与者模板 | payment-dtx/.../TccParticipantTemplate.java |
| 幂等模板 | payment-dtx/.../IdempotentTemplate.java |
| 支付编排 | payment-payment/.../service/PaymentService.java |
| 事务性终态+outbox | payment-payment/.../service/PaymentCompletionListener.java |
| outbox 投递 | payment-payment/.../service/OutboxDispatcher.java |
| 双调度 | payment-payment/.../service/PaymentScheduler.java |
| 复式记账 | payment-accounting/.../service/AccountingService.java |
| 订单状态机 | payment-trade/.../service/TradeService.java |
| 渠道状态机 | payment-channel/.../service/ChannelService.java |
| 通知推送 | payment-notify/.../service/NotifyPusher.java |
| 五方对账 | payment-recon/.../service/ReconService.java |
| OpenAPI 验签 | payment-gateway/.../web/OpenApiController.java |
| 错误映射 | payment-gateway/.../web/GlobalExceptionHandler.java |

## 附录 B：运行速查

```bash
cd /Users/hegan/Documents/Qoder/payment-pro
./run.sh                 # 编译 + 启动 13 进程（--no-build 跳过编译）
./stop.sh                # 优雅停止（数据保留）
open http://127.0.0.1:8080/
python3 smoke-test.py    # 79 项功能断言
python3 verify-pro.py    # 22 项并发/混沌/持久化断言
```

---

## 附录 C：数据字典（字段级 DDL）

> 以下 DDL 逐字取自各域 `src/main/resources/schema.sql`，由 Spring SQL init 在启动时幂等执行（`CREATE ... IF NOT EXISTS`）。
> 通用约定：主键多为 `VARCHAR` 业务 ID（前缀 + 序列）；时间戳统一 `BIGINT` 毫秒；金额统一 `BIGINT` 分；JSON 载荷用 `CLOB`。
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
    biz_type   VARCHAR(32) NOT NULL,          -- TOPUP
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
| accounting | idempotent_record | (TOPUP,bizNo) | 充值重试 |
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
| balance | accountId | Long | — | 只读 |
| balances | List\<accountIds> | Map\<id,Long> | — | 只读 |
| entries | accountId, limit | List\<EntryDTO> | — | 只读（limit≤500） |
| transfer | bizNo | TransferDTO | A0404 | 只读 |
| trial | — | TrialDTO{totalDebit,totalCredit,balanced,balanceSum,accounts,entries} | — | 只读 |

`EntryDTO`：id/bizNo/accountId/dir/amount/balanceAfter/subject/refId/createdAt。
`TransferDTO`：bizNo/lines[TransferLineDTO{accountId,dir,amount}]/subject/refId/reversed/createdAt。

### D.4 TradeFacade

| 方法 | 入参 | 返回 data | 主要错误 | 幂等键 |
|---|---|---|---|---|
| createOrder | OrderCreateRequest{merchantId,subject,amount} | OrderDTO | A0400 非商户/subject；B0001 商户冻结 | — |
| listOrders | — | List\<OrderDTO> | — | 只读 |
| getOrder | orderId | OrderDTO | A0404 | 只读 |
| closeOrder | orderId | OrderDTO | A0409 非 CREATED | CAS |
| refundMark | orderId, amount, bizNo | OrderDTO | A0409 未支付；B0001 超退 | (REFUND_MARK,bizNo) |

`OrderDTO`：id/merchantId/merchantName/subject/amount/status/paymentId/refundedAmount/createdAt/paidAt/closedAt。

### D.5 PaymentFacade

| 方法 | 入参 | 返回 data | 主要错误 | 幂等键 |
|---|---|---|---|---|
| pay | PayRequest{orderId,method,accountId,cardNo,requestId,callbackUrl} | PayResultDTO{payment,repeated} | A0400 requestId/method/账户/卡号；A0409 订单状态；B0001 余额不足/冻结/风控/渠道 | request_id |
| refund | paymentId, RefundRequest{amount,requestId} | RefundResultDTO{payment,refundAmount,refundId} | A0400 amount；A0409 未成功；B0001 超退 | request_id |
| listPayments | — | List\<PaymentDTO> | — | 只读 |
| getPayment | paymentId | PaymentDTO | A0404 | 只读（轮询终态用） |

`PaymentDTO`：id/orderId/method/payerAccountId/cardNo(脱敏)/merchantAccountId/amount/fee/status/refundedAmount/refundedFee/channelBizNo/failReason/requestId/xid/createdAt。
> pay/refund 成功返回时支付单多为 PROCESSING（二阶段异步），客户端轮询 getPayment 至 SUCCESS/FAILED。`repeated=true` 表示幂等回放。

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

### E.1 开启全局事务与注册分支 — `payment-dtx/.../TccCoordinator.java:45`

```java
public String begin(String bizType, String bizNo) {
    String xid = "T" + UUID.randomUUID().toString().replace("-", "");
    try {
        jdbc.update("INSERT INTO dtx_transaction(...,status,...) VALUES(?,?,?,?,...)",
                xid, bizType, bizNo, GlobalStatus.TRYING.name(), ...);
    } catch (DuplicateKeyException e) {                       // :52
        throw new BizException(ResultCode.CONFLICT, "分布式事务已存在: " + bizType + "/" + bizNo);
    }
    return xid;
}
public String registerBranch(String xid, String participant, String action, Object context) {  // :58
    Integer seq = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM dtx_branch WHERE xid=?", ...);
    String branchId = xid + "-B" + seq;
    jdbc.update("INSERT INTO dtx_branch(...,status,context,seq) VALUES(?,?,?,?,?,?,?)",
            xid, branchId, participant, action, BranchStatus.INIT.name(), TccContextUtil.toJson(context), seq);
    return branchId;
}
```

**为什么这样写**：
- `begin` 用 `(biz_type,biz_no)` 唯一约束做兜底——即使上层幂等失效，同一支付单也开不出第二个全局事务（A0409）。
- 分支 `context` 序列化成 JSON **落库**而非放内存：协调器重启后 `recover()` 能从库里读出原样上下文重放二阶段，这是崩溃恢复的前提。
- `seq` 决定 Try/Confirm/Cancel 的执行顺序，注册顺序即驱动顺序。

### E.2 一阶段同步 Try — `TccCoordinator.java:67`

```java
public void tryAll(String xid) {
    String failReason = null;
    for (BranchRow b : branches(xid)) {                       // 按 seq 序
        try {
            participant(b).tryAction(request(b)).orThrow();
            updateBranch(b, BranchStatus.TRY_OK);
        } catch (Exception e) {
            updateBranch(b, BranchStatus.TRY_FAIL);
            failReason = e.getMessage();
            break;                                            // :77 首个失败即停
        }
    }
    if (failReason != null) {
        jdbc.update("UPDATE dtx_transaction SET status='CANCELLING', fail_reason=? ...", ...);
        driveCancel(xid);                                     // :83 立即同步回滚
        throw new BizException(ResultCode.BIZ_ERROR, failReason);  // :84 抛给调用方
    }
}
```

**为什么这样写**：Try 必须同步——余额不足/渠道拒绝要立刻给用户明确错误。任一失败立即 `driveCancel` 把已预留资源释放（未 Try 的分支走空回滚），再抛业务异常。失败分支之后的分支不再 Try（break），减少无谓预留。

### E.3 二阶段驱动与事务性终态 — `TccCoordinator.java:140`

```java
private void driveConfirm(String xid) {
    for (BranchRow b : branches(xid)) {
        if (b.status() == BranchStatus.CONFIRMED) continue;   // :142 分支级断点续传
        if (b.status() != BranchStatus.TRY_OK)                // :145 未就绪不允许提交
            throw new BizException(ResultCode.BIZ_ERROR, "提交阶段存在未就绪分支: ...");
        try {
            participant(b).confirm(request(b)).orThrow();
            updateBranch(b, BranchStatus.CONFIRMED);
        } catch (Exception e) {
            return;                                          // :153 失败留给补偿，不抛
        }
    }
    tx.executeWithoutResult(s -> {                            // :156 ★ 同一本地事务
        updateGlobal(xid, GlobalStatus.CONFIRMED);            //   全局终态
        if (listener != null) listener.onConfirmed(xid);      //   支付单翻转 + outbox 写入
    });
}
```

**为什么这样写**：这是事务性 outbox 的根基。全局终态、支付单 SUCCESS、outbox 两条消息在**一个本地事务**里提交——不存在"事务已提交但消息丢了"或"消息发了但状态没落"的中间态。Confirm 失败 `return` 而非抛异常，让 `bumpRetryIfPending` 登记退避，由 `recover()` 续驱；已 CONFIRMED 分支跳过实现断点续传。`driveCancel`（:165）对称，回调 `onCancelled` 落 FAILED。

### E.4 补偿恢复与退避耗尽 — `TccCoordinator.java:103` / `:188`

```java
public void recover() {
    List<Map<String,Object>> due = jdbc.queryForList(
        "SELECT xid,status FROM dtx_transaction WHERE status IN ('TRYING','CONFIRMING','CANCELLING') AND next_retry_at <= ?", now);
    for (var row : due) {
        switch (status) {
            case CONFIRMING -> driveConfirm(xid);
            case CANCELLING -> driveCancel(xid);
            case TRYING -> {                                 // :114 一阶段崩溃决策
                boolean allOk = !bs.isEmpty() && bs.stream().allMatch(b -> b.status()==TRY_OK);
                if (allOk) commitAsync(xid);                 //   全 Try 成功 → 提交
                else { /* 置 CANCELLING */ driveCancel(xid); } //   否则 → 回滚
            }
        }
        bumpRetryIfPending(xid);
    }
}
private void bumpRetryIfPending(String xid) {                 // :188
    if (终态) return;
    int count = retry_count + 1;
    if (count >= MAX_RETRY) {                                // :200 MAX_RETRY=8
        jdbc.update("UPDATE dtx_transaction SET status='DEAD' ...");  // 耗尽置 DEAD
        log.error("TCC 补偿重试耗尽，置 DEAD 待人工介入 xid={} retry={}", ...);
        return;
    }
    long delay = BACKOFF_SECONDS[Math.min(count-1, len-1)] * 1000L;  // {5,10,20,40,80,160,320,640}
    jdbc.update("UPDATE ... SET retry_count=?, next_retry_at=? ...", count, now+delay);
}
```

**为什么这样写**：TRYING 态恢复是协调器一阶段崩溃的兜底——分支日志持久化使得"全 TRY_OK 则提交、否则回滚"可判定。退避累计约 21 分钟，耗尽不无限重试而是显式置 DEAD，把无法自动收敛的事务暴露给对账与人工，避免静默吞掉资金不一致。

### E.5 参与者 Try：幂等/防悬挂/失败清理 — `payment-dtx/.../TccParticipantTemplate.java:39`

```java
public void tryAction(TccBranchRequest req, Runnable business) {
    tx.executeWithoutResult(s -> {                           // REQUIRES_NEW（ctor :36）
        try {
            insertLog(req, ParticipantStatus.TRYING);
        } catch (DuplicateKeyException e) {
            ParticipantStatus existing = currentStatus(req);
            if (existing == ParticipantStatus.CANCELLED)      // :45 防悬挂
                throw new BizException(ResultCode.CONFLICT, "TCC 悬挂拒绝: ...");
            return;                                          // :49 幂等命中，不重复执行
        }
        try {
            business.run();
        } catch (RuntimeException e) {
            jdbc.update("DELETE FROM tcc_branch_log WHERE xid=? AND branch_id=? AND action=?", ...);  // :55
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

### E.6 参与者 Confirm/Cancel：空回滚与状态互斥 — `TccParticipantTemplate.java:62` / `:79`

```java
public void confirm(req, business) {
    tx.executeWithoutResult(s -> {
        ParticipantStatus status = currentStatus(req);
        if (status == null) throw ...("TCC Confirm 无 Try 记录");      // :65 不允许凭空提交
        if (status == CONFIRMED) return;                              // :68 幂等
        if (status == CANCELLED) throw ...("已回滚分支不可提交");       // :71 互斥
        business.run(); updateStatus(req, CONFIRMED);
    });
}
public void cancel(req, business) {
    tx.executeWithoutResult(s -> {
        ParticipantStatus status = currentStatus(req);
        if (status == null) { insertLog(req, CANCELLED); return; }    // :82 ★ 空回滚
        if (status == CANCELLED) return;                              // :87 幂等
        if (status == CONFIRMED) throw ...("已提交分支不可回滚");       // :90 互斥
        business.run(); updateStatus(req, CANCELLED);
    });
}
```

**为什么这样写**：空回滚（Cancel 先于 Try 到达）时查无日志，落 CANCELLED 标记不动业务——这个标记同时成为防悬挂屏障。Confirm/Cancel 的状态互斥保护杜绝"已回滚又提交""已提交又回滚"的资金错乱。

### E.7 通用幂等模板 — `payment-dtx/.../IdempotentTemplate.java:32`

```java
public <T> T execute(String bizType, String bizNo, Class<T> resultType, Supplier<T> action) {
    return tx.execute(s -> {
        try {
            jdbc.update("INSERT INTO idempotent_record(biz_type,biz_no,result,created_at) VALUES(?,?,NULL,?)", ...);  // 占位
        } catch (DuplicateKeyException e) {
            String json = /* SELECT result */;
            if (json == null) throw new BizException(CONFLICT, "幂等请求处理中: ...");  // :41 首请求仍在处理
            return TccContextUtil.fromJson(json, resultType);                          // :45 命中既有结果
        }
        T result = action.get();                                                       // :47 业务
        jdbc.update("UPDATE idempotent_record SET result=? WHERE ...", toJson(result)); // :48 回填
        return result;                                                                 // 同事务提交
    });
}
```

**为什么这样写**：占位（result=NULL）、业务、回填三步同事务。并发重复请求在唯一键上竞争：胜者执行业务并回填，败者要么读到完整结果（结果一致），要么读到 NULL 得到明确"处理中"（A0409），**绝不会重复执行业务**。相比"先查后插"消除了查-插窗口竞态。

### E.8 支付编排流水线 — `payment-payment/.../service/PaymentService.java:64`

```java
public PayResultDTO pay(PayRequest request) {
    requireRequestId(request.getRequestId());                 // :65 幂等键必填
    // method 校验 BALANCE/CARD                                 :67
    PaymentDO replay = paymentMapper.selectByRequestId(...);  // :70 ★ 回放先于状态检查
    if (replay != null) return result(replay, true);
    OrderDTO order = tradeFacade.getOrder(...).orThrow();     // :75 订单 CREATED 校验
    // BALANCE: 付款户 ACTIVE + 余额足额 / CARD: 卡号必填        :80-93
    long fee = MoneyUtil.fee(amount, feeBps);                 // :96 默认 60bps
    try { paymentMapper.insert(payment); }                    // :114 insert-first
    catch (DuplicateKeyException e) { return result(selectByRequestId(...), true); }  // :116 并发败者读胜者
    RiskResultDTO riskResult = riskFacade.check(risk).orThrow();  // :127
    if (!pass) { paymentMapper.finish(id,"FAILED",reason); throw ...; }  // :128 风控拒绝留痕
    String xid = coordinator.begin("PAY", payment.getId());   // :134
    paymentMapper.updateXid(payment.getId(), xid);
    coordinator.registerBranch(xid,"accounting","PAY", accountingContext(...));   // :138
    if (CARD) coordinator.registerBranch(xid,"channel","PAY", channelContext(...)); // :139
    coordinator.registerBranch(xid,"trade","PAY", tradeContext(...));             // :142
    coordinator.tryAll(xid);                                  // :143 同步 Try
    coordinator.commitAsync(xid);                             // :144 异步二阶段
    return result(require(payment.getId()), false);           // :145 立即返回 PROCESSING
}
```

**为什么这样写**：
- 幂等预检（:70）先于订单状态检查——订单已 PAID 后同 requestId 回放仍返回原始结果而非 409（实测修复过的缺陷）。
- insert-first（:114）+ DuplicateKey 回读（:116）：并发同 requestId 在唯一键竞争，败者读胜者结果。
- 分支注册顺序 accounting→channel→trade：最可能失败的资金约束前置，Try 失败回滚成本最小。
- 风控拒绝先把支付单落 FAILED 留痕再抛错，便于审计与对账。

退款手续费按比例退（`PaymentService.java:165`）：

```java
long feeRefund = amount == remaining
        ? payment.getFee() - payment.getRefundedFee()                       // 最后一笔退剩余全部手续费
        : (payment.getFee() * amount + payment.getAmount() / 2) / payment.getAmount();  // 四舍五入
```

保证分账精确闭环、无一分钱残留。

### E.9 事务性终态回调 — `payment-payment/.../service/PaymentCompletionListener.java:35`

```java
public void onConfirmed(String xid) {                         // 在 driveConfirm 的同一事务内被调用
    PaymentDO payment = paymentMapper.selectByXid(xid);
    if (payment != null && "PROCESSING".equals(payment.getStatus())) {
        paymentMapper.finish(payment.getId(), "SUCCESS", null);             // :39 支付单翻转
        outboxMapper.insert("SETTLE_PAY", payment.getId(), toJson(settle), now);   // :48
        outboxMapper.insert("NOTIFY_PAY", payment.getId(), toJson(notify(...)), now); // :55
    }
    RefundDO refund = refundMapper.selectByXid(xid);
    if (refund != null && "PROCESSING".equals(refund.getStatus())) {
        refundMapper.finish(refund.getId(), "SUCCESS");
        paymentMapper.updateRefunded(pay.getId(), refundedAmount+..., refundedFee+...);  // :62 累计退款
        outboxMapper.insert("SETTLE_REFUND", ...); outboxMapper.insert("NOTIFY_REFUND", ...);
    }
}
```

**为什么这样写**：`"PROCESSING".equals(status)` 守卫保证回调重放幂等——已是终态则跳过。支付单翻转与 outbox 写入和全局终态同事务（E.3），三者原子。退款额外累计 refunded_amount/refunded_fee，供后续退款比例计算与终态翻转。

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
    }
}
```

**为什么这样写**：至少一次投递；接收端 settle/notify 用 IdempotentTemplate 去重 → 恰好一次效果。投递失败退避重试，耗尽 DEAD 由对账"支付-清结算一致"项精确报出缺失笔数。双调度 `PaymentScheduler.java:16`（recover 5s）/`:21`（dispatch 3s）均 `fixedDelay`，单实例无重叠。

### E.11 账务复式记账 — `payment-accounting/.../service/AccountingService.java:177`

```java
public void post(String bizNo, List<TransferLineDTO> lines, ..., boolean reversed) {
    validateLines(lines);                                     // :178 借贷平衡强校验（D==C）
    checkAccounts(...);                                       // :179 非系统户 ACTIVE 校验
    for (TransferLineDTO line : sorted(lines)) {              // :181 ★ 按 accountId 排序加锁 → 无死锁
        BalanceDO balance = lockOrCreate(line.getAccountId());  // SELECT FOR UPDATE
        if ("D".equals(line.getDir())) {
            long available = balance.getBalance() - balance.getFrozen();
            if (!isSystem(...) && available < amount) throw ...("可用余额不足");  // :187
            newBalance = balance.getBalance() - amount;
        } else newBalance = balance.getBalance() + amount;
        balanceMapper.update(balance);
        // 落分录 ledger_entry（含 balance_after 快照）          :198-210
    }
    try { transferMapper.insert(record); }                  // :219 biz_no PK 幂等兜底
    catch (DuplicateKeyException e) { log.info("转账记录已存在（幂等）..."); }
}
```

TCC 三动作（`freeze:133` / `postAndUnfreeze:153` / `unfreeze:160`）：

```java
public void freeze(ctx) {            // Try：只冻借方非系统户
    for (line : sorted(ctx.getLines())) {
        if (!"D".equals(dir) || isSystem(accountId)) continue;
        BalanceDO b = lockOrCreate(...);
        if (b.getBalance()-b.getFrozen() < amount) throw ...("可用余额不足");
        b.setFrozen(b.getFrozen()+amount); balanceMapper.update(b);
    }
}
public void postAndUnfreeze(ctx) {   // Confirm：★ 先解冻再过账
    unfreeze(ctx);                   // :155 否则全额退款时冻结额≥余额一半会误报可用不足
    post(ctx.getBizNo(), ctx.getLines(), ..., reversed);
}
public void unfreeze(ctx) {          // Cancel：仅解冻，Math.max(0, frozen-amount) 防御
    ...
}
```

**为什么这样写**：排序加锁保证全局一致加锁顺序→无死锁；`lockOrCreate`（:268）并发开户竞争捕获 DuplicateKey 后加锁重读。Confirm 先解冻再过账的次序是实测修复的关键——过账的可用余额校验若受本笔冻结影响，全额退款会误报不足。系统户（SYS_ 前缀）可透支不冻。

### E.12 参与者装配与路由 — `payment-accounting/.../facade/AccountingTccFacadeImpl.java:15`

```java
@DubboService(group = "accounting")                           // 同接口按 group 区分实现
public class AccountingTccFacadeImpl implements TccParticipantFacade {
    private final TccParticipantTemplate template;            // DtxAutoConfiguration 自动装配
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

**为什么这样写**：参与者只写业务 Runnable，幂等/空回滚/防悬挂全部由模板承担（关注点分离）。`PaymentConfig.java:14` 把 `asMap()` 注入 `TccCoordinator`，协调器按分支日志 participant 名路由——新增参与者只需注册 bean + registerBranch，协调器零改动。trade 参与者按 action 分流（`TradeTccFacadeImpl.java:29`：REFUND→checkRefundable/refundMark，PAY→reserve/markPaid/release）。

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
| 10 | 空回滚 | Try 超时/丢失，Cancel 先到 | 查无日志 → 落 CANCELLED 标记，不动业务，返回成功 | cancel:82 | [码] |
| 11 | 防悬挂 | 被空回滚后迟到 Try | INSERT 冲突且既有 CANCELLED → 抛 CONFLICT 拒绝预留 | tryAction:45 | [码] |
| 12 | Try 业务失败留痕 | freeze/preauth 抛异常 | 同事务 DELETE 日志 → 后续 Cancel 走空回滚，不误解冻 | tryAction:55 | [S] |
| 13 | 已回滚分支被 Confirm | 状态错乱 | status=CANCELLED → 抛"已回滚分支不可提交" | confirm:71 | [码] |
| 14 | 已提交分支被 Cancel | 状态错乱 | status=CONFIRMED → 抛"已提交分支不可回滚" | cancel:90 | [码] |
| 15 | 凭空 Confirm | 无 Try 记录直接 Confirm | status=null → 抛"无 Try 记录" | confirm:65 | [码] |

### F.3 崩溃与宕机类（持久化恢复）

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 16 | 协调器一阶段崩溃（部分 Try） | Try 执行中宕机 | recover 见 TRYING：非全 TRY_OK → 回滚 | 分支日志持久化 | [D][码] |
| 17 | 协调器一阶段崩溃（全 Try 成功） | Try 全成功未及 commit | recover 见 TRYING：全 TRY_OK → 提交 | recover:114 | [D][码] |
| 18 | Confirm 部分成功崩溃 | 部分分支 CONFIRMED | recover 续驱，跳过 CONFIRMED 分支 | driveConfirm:142 | [C] |
| 19 | 终态事务未提交崩溃 | Confirm 成功但本地事务未落 | 参与者幂等跳过，终态事务重做 | 幂等+本地事务原子 | [码] |
| 20 | 参与者宕机（渠道进程被杀） | CARD 支付 Confirm RPC 失败 | CONFIRMING 挂起，支付单 PROCESSING；渠道恢复后 recover 续驱收敛 SUCCESS | 退避重试 | [C] |
| 21 | 全停重启 | stop.sh→run.sh | H2 落盘，schema IF NOT EXISTS 幂等建表，在途事务/outbox 恢复，快照一致 | 文件库持久化 | [D] |
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
| 33 | 并发开户竞争 | 首笔业务懒开户 | lockOrCreate INSERT 竞争捕获 DuplicateKey 后加锁重读 | lockOrCreate:268 | [码] |
| 34 | 多账户转账死锁 | 并发交叉转账 | 按 accountId 排序加锁，全局一致顺序 → 无死锁 | sorted:292 | [码] |

### F.5 异步投递与通知边界

| # | 场景 | 触发 | 系统行为 | 兜底 | 验证 |
|---|---|---|---|---|---|
| 35 | outbox 投递失败 | settle/notify 不可达 | attempts+1 退避 {3,6,12,24,48,96,192,384}s | 退避重试 | [码] |
| 36 | outbox 重试耗尽 | attempts≥8 | 置 DEAD + error 日志，对账"支付-清结算一致"报缺失笔数 | DEAD+对账 | [码] |
| 37 | 通知死链 | callbackUrl 不可达 | PENDING 退避 {5,10,20,40,80}s，attempts≥5 → FAILED（记录永久可查，商户可 PULL 补查） | NotifyPusher | [S] |
| 38 | 无 callbackUrl | 商户未配回调 | 登记即 PULL 模式，不推送 | PULL | [S] |
| 39 | TCC 补偿耗尽 | retry≥8 | 置 DEAD + error，待人工/对账介入，不无限重试 | bumpRetryIfPending:200 | [码] |

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