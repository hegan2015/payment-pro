# payment-pro 详细设计文档

> 参考支付宝领域划分的工业级支付系统
> Spring Boot 3.2 + Apache Dubbo 3.2 + 自研 XTS 风格 TCC + 事务性 Outbox
> 版本：1.0.0 ｜ 文档日期：2026-11-16

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
balance            -- account_id PK、balance（贷-借累计）、frozen、version
entry              -- 分录：seq 序列、biz_no、account_id、dir(D/C)、amount、balance_after
transfer           -- 转账凭证：biz_no UNIQUE（过账幂等兜底）、lines JSON、reversed 标记
```

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
UPDATE orders SET status='PAYING', payment_id=? WHERE id=? AND status='CREATED'
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