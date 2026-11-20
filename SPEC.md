# payment-pro 业务·架构·管理端 全量拆解规格（SPEC）

> 版本 v1.0 · 2026-11-19 · 依据 DESIGN.md 1.8.0 + ROADMAP.md v1.0 + ui-design 原型
> 文档链：**ROADMAP**(范围/优先级) → **SPEC**(本文:业务语义+架构迁移+管理端功能) → **IMPLEMENTATION-PLAN**(代码任务卡,编制中) → **ui-design**(视觉原型)
> 标注约定：`[现]`=当前已实现；`[升]`=P0/P1/P2 升级新增。金额一律 long「分」。

---

## 1. 业务单元总览

| 域 | 核心实体 | 状态机 | 权威数据 | 对外 Facade |
|---|---|---|---|---|
| member 会员 | Member(USER/MERCHANT) | ACTIVE/FROZEN | 身份/appId/appSecret | MemberFacade |
| account 账户 | Account | ACTIVE/FROZEN(+细分[升]) | 账户实体/状态(不存余额) | AccountFacade |
| accounting 账务 | Entry/Transfer/Account余额 | — | **余额唯一权威**/分录 | AccountingFacade |
| trade 交易 | Order | CREATED→PAYING→PAID→(PARTIAL_)REFUNDED/CLOSED | 订单生命周期 | TradeFacade |
| payment 支付核心 | Payment/Refund | PROCESSING→SUCCESS/FAILED/REFUNDED | 支付单/TCC xid/outbox | PaymentFacade |
| cashier 收银台 | —(纯聚合) | — | 无 | CashierFacade |
| channel 渠道 | ChannelTrade | PREAUTH→CAPTURED/VOIDED/REFUNDED | 渠道交易 | ChannelFacade |
| risk 风控 | —(规则) | — | 无(窗口在内存/Redis) | RiskFacade |
| settle 清结算 | SettleRecord | PAY/REFUND 登记 | 结算单 gross/fee/net | SettleFacade |
| notify 通知 | NotifyRecord | PULL/PENDING/DELIVERED/FAILED | 通知记录 | NotifyFacade |
| recon 对账 | ReconReport | 六项核对 pass/fail | 对账报告 | ReconFacade |
| gateway 网关 | —(入口) | — | 无 | HTTP/OpenAPI |

依赖原则：网关/收银台/对账纯聚合不持数据；payment 是唯一 TCC 编排者；accounting 是余额唯一权威；跨域零共享库。

---

## 2. 分域拆解：流程 / 逻辑 / 规则

### 2.1 member 会员
**流程** `[现]` 注册(type 校验→重名 409→ACTIVE)→商户发 appId/appSecret→冻结/解冻(CAS)。
`[升]` 进件：提交资质→PENDING_REVIEW→审核(通过/驳回)→签约产品→配费率→绑结算卡→ACTIVE；KYC：四要素核验→实名分级(I/II/III)；密钥轮换：新旧双密钥并存→过期下线。
**逻辑** appId 全局唯一；状态 CAS；`getByAppId` 供网关验签；密钥版本化。
**规则** USER 不可下单(400)；冻结商户下单 400；重名 409；`[升]` 分级限额联动账户；进件资质必填、驳回可重提；轮换期双密钥均验签通过。

### 2.2 account 账户
**流程** `[现]` 开户(memberId→uk_account_member 幂等)→状态管理；`check(ids)` 批量供账务过账前校验。
`[升]` 多账户类型(余额/保证金/子账户/营销/待结算)；绑卡(四要素→签约→默认卡)；冻结细分(只收不付/只付不收/全额/司法)；多币种。
**逻辑** 不存余额(权威在账务)；`get` 回填余额走账务；状态 CAS；懒开户在账务 lockOrCreate。
**规则** 一会员一默认账户；`[升]` 冻结方向语义(只收不付=禁借方/只付不收=禁贷方)；解绑不影响在途交易；司法冻结优先于用户操作。

### 2.3 accounting 账务（资金正确性核心）
**流程** `[现]` topup/post(通用过账)/TCC 三动作/freeze/unfreeze/trial。
`[升]` 科目体系→日切(会计日切换+损益结转)→红冲蓝补(冲正凭证)→账簿(总账/明细/余额表)→账实核对( vs 银行存管)。
**逻辑**
- `balance=贷累计−借累计`；`可用=balance−frozen`。
- 排序加锁：多账户按 accountId 排序逐个 SELECT FOR UPDATE → 全局一致无死锁。
- lockOrCreate：并发 INSERT 捕 DuplicateKey 后加锁重读。
- TCC：Try=freeze(只冻借方非系统户)；Confirm=**先解冻再过账**(避免全额退款冻结≥余额一半误报不足)；Cancel=unfreeze(`max(0,frozen−amt)`)。
- post：借贷平衡校验→排序加锁→逐行更新余额+落分录(balance_after 快照)+落凭证(bizNo 幂等)。
- 幂等键：(TOPUP,bizNo)/(POST,bizNo)。
**规则** 系统户(SYS_CASH/SYS_CLEARING/SYS_FEE)可透支；非系统户借方须足额可用；借贷净额=0；封闭系统 Σbalance=0；`[升]` 冲正幂等且原凭证不可篡改；日切凭证归属会计日；账实差异生成差错单。

### 2.4 trade 交易
**流程** `[现]` CREATED─reserve→PAYING─markPaid→PAID─refundMark→PARTIAL_REFUNDED→REFUNDED；close→CLOSED；release(Cancel)→CREATED。
`[升]` 担保：付款→资金入担保户→买家确认收货(或超时自动)→打款卖家；预授权：冻结→完成(扣≤冻)/撤销(全解冻)；超时自动关闭(延迟消息)；合并支付/分阶段。
**逻辑** 全 CAS；release 带 paymentId 只释放本单预留；markPaid 幂等(已 PAID 直返)；refundMark=(REFUND_MARK,bizNo)+累计校验；checkRefundable 只校验不落状态(退款预留由账务冻结承载)。
**规则** 已 PAID/REFUNDED reserve→409；CLOSED→409；CAS 失败→409 正在支付；超退拦截；`[升]` 担保确认前资金在担保户不可挪用；预授权完成额≤冻结额；超时策略可配。

### 2.5 payment 支付核心（编排中枢）
**流程** `[现]` pay：requestId 必填→幂等预检→method 校验→订单 CREATED→商户账户定位→BALANCE 余额足额/CARD 卡号→计费 fee=amount*feeBps→insert PROCESSING(request_id UNIQUE)→风控 check(拒→FAILED 留痕)→begin("PAY")→registerBranch(accounting→[channel]→trade)→tryAll(同步,败整体回滚)→commitAsync→返回 PROCESSING 轮询。refund 对称+手续费比例退。fundPay：充值/提现=交易单+支付单(SYS_FUND/fee=0/BALANCE/无渠道)。
`[升]` 组合支付(多腿)；分账编排(实时/延迟/多方+回退)；退款异步化(退款单状态机+渠道回执+补单)；关闭/撤销；查询补单；支付路由；营销抵扣核销。
**逻辑** request_id UNIQUE 幂等(repeated=true 回放)；分支顺序资金最先/渠道次/订单最后(回滚成本最小)；终态 SQL 原子翻转 `status=CASE WHEN refunded>=amount THEN REFUNDED`；feeRefund=(fee*amt+total/2)/total，**最后一笔退剩余全部手续费**保证无残留。
**规则** 仅 SUCCESS 可退；累计可退双重校验(核心+交易)；资金单不可退；`[升]` 组合各腿合计=订单额、一腿败整体回滚；分账合计≤可分额、回退幂等；营销核销幂等+分摊守恒。

### 2.6 cashier 收银台
**流程** `[现]` cashier(orderId) 聚合订单+可用方式(BALANCE 附活跃账户)；pay 透传核心。
`[升]` 多方式渲染(余额/快捷/网银/信用/券/组合)；付款码(被扫)/扫码(主扫)；营销最优组合计算；支付密码/生物/免密；多端。
**逻辑** 纯聚合不持数据、不做业务决策；码生成/解析/过期。
**规则** 收银台只渲染与受理；`[升]` 码每分钟刷新+防重放；免密限额；验证失败拦截。

### 2.7 channel 渠道
**流程** `[现]` PAY:PREAUTH─capture→CAPTURED，─voidAuth→VOIDED；REFUND:PREAUTH─capture→REFUNDED。
`[升]` 真实渠道 SPI(沙箱+生产)；路由(成功率/成本/限额择优+故障切换)；异步回执+主动补单；对账文件下载解析；限额/维护窗口；签约代扣。
**逻辑** CAS 状态机；独立进程可单独停做混沌；TCC 补偿恢复后收敛；模拟银行保留为 local 降级。
**规则** 尾号 0000→Try 拒绝(模拟风控)；REFUND Try 须原 CAPTURED；`[升]` 渠道限额超限拒；维护期路由绕行；回执乱序/丢失靠补单收敛。

### 2.8 risk 风控
**流程** `[现]` check(payerId,merchantId,amount,method)→{pass,reason}。
`[升]` 规则引擎(可视化配置+动态下发+灰度回滚)；多维限额；名单(黑/白/灰)；评分+分级处置(放行/增强认证/拦截/人审)；设备指纹/IP 画像；AML 大额可疑报告。
**逻辑** RateLimiter 抽象双实现：local 内存(ConcurrentHashMap+Deque+窗口锁)/docker Redis Lua(ZSET ZREMRANGEBYSCORE→ZCARD→ZADD+PEXPIRE)；payerId BALANCE=accountId、CARD="CARD:"+cardNo。
**规则** `[现]` 单笔≤10,000,000 分(10 万)；同 payer 10s≤5 笔。`[升]` 单笔/日/月/年/累计×用户/商户/卡/渠道；名单命中处置；评分阈值可配；AML 命中生成报告。

### 2.9 settle 清结算
**流程** `[现]` recordPay/recordRefund→结算单(gross/fee/net)；summary 汇总。
`[升]` T+N 周期批次+日切结算；阶梯费率/分成；分账(实时/延迟/多方/比例/固定+回退)；提现到银行(出金+到账回执)；商户日/月账单；保证金/垫资。
**逻辑** (SETTLE_PAY,paymentId)/(SETTLE_REFUND,bizId) 幂等；REFUND 须找到原 PAY(乱序防护:PAY 未到则退避等待)；summary：totalFee=ΣPAY.fee−ΣREFUND.fee、totalNet=ΣPAY.net−ΣREFUND.net。
**规则** 净手续费=SYS_FEE 余额；净结算额=商户余额增量(与账务恒等式对齐)；`[升]` T+N 到期出批次；分账守恒；出金失败重试/差错。

### 2.10 notify 通知
**流程** `[现]` send 登记(event:bizId 幂等)→PENDING→HTTP 推送→DELIVERED/FAILED 退避重试；records/get 永久可查。
`[升]` 通知体签名+HTTPS；多事件(支付/退款/分账/结算/签约)；查询/重发 API；死信告警+工单。
**逻辑** 登记幂等；退避重试；事务消息可靠投递。
**规则** `[升]` 商户可验签、篡改/重放拒；重试耗尽告警；重发幂等。

### 2.11 recon 对账
**流程** `[现]` run 六项核对(逐项故障隔离)→报告 JSON 落库→latest 查历史。
`[升]` 日切批量对账；渠道对账文件解析(内外对账)；差错自动处理(长款/短款/金额/状态→冲正/挂账/工单)；报表/进度；XXL-Job 分片。
**逻辑** detailAction 统计缺失→failSignal 读同源计数判 pass；PROCESSING 在途单不参与核对(防误报)。
**规则** 六项：账务 D=C 且 Σ=0；每 SUCCESS 支付等额借方；订单状态∈{PAID,PARTIAL_REFUNDED,REFUNDED}；每支付有 PAY 结算单；每 CARD 支付有 CAPTURED/REFUNDED 渠道单；`[升]` 差错分类→自动冲正/挂账。

### 2.12 gateway 网关
**流程** `[现]` HTTP 入口→OpenAPI 验签(appId/sign/timestamp±300s→TreeMap 排序剔 sign→HmacSHA256→hex 比对)→method 映射 facade；控制台聚合 /api/*。
`[升]` OAuth2+RBAC+审计；开放平台(token/沙箱/限流计费/版本/SDK)；Sentinel 限流熔断；OpenAPI 文档。
**逻辑** MerchantCache Redis TTL60s 异常降级直查；卡号脱敏(前6后4)；错误码不泄漏堆栈/SQL。
**规则** 验签失败 400；时差>300s 拒(防重放)；金额 long 分；冻结在下单/支付/过账三处重复校验(纵深)。

---

## 3. 架构升级拆解（现状→目标→迁移→双profile→回滚）

| 组件 | 现状 | 目标 | 迁移步骤 | local 降级 | 回滚/风险 |
|---|---|---|---|---|---|
| 注册中心 | 内嵌/共享 ZK | **维持 ZK**(红线)；集群化可选 P2 | 不动选型；集群化加节点 | 内嵌 ZK | 不改即无风险 |
| 配置中心 | 环境变量 | **Apollo**(与 ZK 解耦) | 接 Apollo→费率/限额/规则/开关迁配置→监听热更 | 本地 properties 兜底 | 配置回滚版本；误配灰度 |
| 调度 | 单实例 @Scheduled | **XXL-Job**(ops :8094)/ShedLock | 任务注册执行器→分片→替换 @Scheduled | @Scheduled 保留 | 双跑期防重(分布式锁) |
| 消息 | outbox 轮询+MQ 主投递 | **RocketMQ 事务消息**+多 topic | 半消息+回查→分 topic→灰度切 | outbox 轮询兜底 | 回切轮询；投递语义回归 |
| 存储 | 单实例多库 pp_* | **ShardingSphere** 分片+读写分离 | 非资金域试点→资金域分片(对账护航)→切流 | 单库兼容 | 分片键选型错=跨片查询；保留单库 |
| TCC | tcc-core+静态 Map | **保留 tcc-core**；参与者自注册 | 组件内加注册/发现(不迁 Seata) | 现状 | 崩溃恢复矩阵不回归 |
| 网关治理 | 无 | **Sentinel** 限流/熔断/降级 | 埋点→规则→防重放防刷 | 直通 | 误限流灰度 |
| 可观测 | traceId 日志 | **Micrometer+Prometheus+Grafana**+告警 | 指标埋点→面板→告警(差错/DEAD/通知耗尽) | 日志 | 无 |
| 密钥 | 配置文件 | **KMS/Vault**+RSA2 证书 | 密钥入库→轮换→证书模式 | 本地加密文件 | 轮换不停机 |
| 认证 | 控制台无登录 | **Spring Security OAuth2+RBAC** | 接认证→角色→审计 | 开发态放行开关 | 权限误配回滚 |
| 多实例 | 单进程 | 无状态化+灰度 | 会话外置→滚动→灰度 | 单进程 | 灰度可回滚 |

---

## 4. 管理端 UI 功能拆解（对齐支付宝商家中心 + ui-design 原型）

> 角色见 §7 权限矩阵。每模块列：功能点 / 关键字段 / 操作 / 对应 facade·升级编号。

### 4.1 概览 Dashboard
功能：今日交易额/笔数/待结算/对账状态四指标卡；7 日趋势图；五方对账面板；最近交易表；告警入口。
字段：gross/count/待结算 net/对账 pass·差错数/环比。操作：导出报表、跳详情、时间范围切换。→ SettleFacade.summary/ReconFacade.latest/PaymentFacade.listPayments。

### 4.2 交易管理
功能：交易列表(分页/筛选)、详情(订单+支付单+渠道+分录链路)、关闭/撤销、查询补单、对账单下载。
字段：orderId/outTradeNo/金额/方式/状态/渠道 bizNo/时间。操作：close、cancel、补单、导出。→ Trade/Payment/ChannelFacade。[升]P-5/P-1。

### 4.3 退款
功能：退款单列表、发起退款(全额/部分)、退款详情(异步状态机+渠道回执)、超退校验提示、退款审批(大额)。
字段：refundId/paymentId/金额/手续费退/状态/回执。操作：refund、重试、审批。→ PaymentFacade.refund。[升]P-4/S-7。

### 4.4 分账
功能：分账规则配置(比例/固定/多方)、分账执行(实时/延迟)、分账明细、分账回退、可分额校验。
字段：ruleId/接收方/比例/金额/状态。操作：建规则、执行、回退。→ [升]P-3/ST-3 新增 SplitFacade。

### 4.5 清结算
功能：结算批次(T+N)、结算单明细(gross/fee/net)、费率配置(阶梯/分成)、提现到银行、商户账单(日/月)下载、保证金管理。
字段：batchId/周期/净额/费率/出金状态。操作：出批次、配费率、发起出金、下载账单。→ SettleFacade。[升]ST-1/2/4/5/6。

### 4.6 对账中心
功能：实时五方核对、日切批量对账、渠道对账文件上传/解析、差错列表(长款/短款/金额/状态)、差错处理(自动冲正/挂账/人工工单)、对账报表/进度。
字段：reportId/核对项/pass/缺失数/差错类型/处理状态。操作：run、上传文件、冲正、挂账、工单。→ ReconFacade+差错平台。[升]RC-1~5/X-1。

### 4.7 消息通知
功能：通知记录(按 bizId)、送达状态、重发、通知模板/签名配置、死信告警列表。
字段：notifyId/event/状态/重试次数/签名。操作：重发、验签测试、告警确认。→ NotifyFacade。[升]N-1~4。

### 4.8 风控
功能：规则配置(可视化规则引擎)、限额配置(多维)、名单管理(黑/白/灰)、风险事件/评分、处置记录(放行/增强/拦截/人审)、AML 报告、规则灰度/回滚。
字段：ruleId/维度/阈值/名单类型/评分/处置。操作：建规则、灰度、加名单、人审。→ RiskFacade+规则引擎。[升]R-1~6。

### 4.9 营销
功能：活动/红包/优惠券/立减配置、核销记录、营销资金分摊、最优组合预览、活动上下线。
字段：activityId/类型/面额/门槛/分摊/核销状态。操作：建活动、上线、查核销。→ [升]X-3 新增 MarketingFacade。

### 4.10 开放平台
功能：应用(appId)管理、密钥/证书轮换、API 授权/版本、沙箱环境、限流/计费配置、SDK/文档、调用日志。
字段：appId/密钥版本/权限/配额/调用量。操作：建应用、轮换、配配额、开沙箱。→ Member/网关。[升]M-1/G-4。

### 4.11 会员/账户/账务（运营侧）
功能：会员列表/KYC 审核/商户进件审核/冻结解冻；账户列表/多类型/绑卡/冻结细分；账务余额/分录全局分页/科目余额表/日切/冲正凭证/试算平衡。
操作：审核、冻结、开户、冲正、日切、查账。→ Member/Account/AccountingFacade。[升]M-2/4/A-1~3/AC-1~5。

### 4.12 系统管理
功能：RBAC 角色/用户/权限、操作审计、配置中心(Apollo)开关、密钥管理(KMS)、监控告警、灰度发布。
操作：授权、查审计、改配置、轮换密钥、灰度。→ [升]S-1/S-2/I-2/I-8。

---

## 5. 跨域端到端流程（升级后全链路）

**余额支付**：cashier→payment.pay→risk→TCC begin→accounting.freeze+trade.reserve→tryAll→commitAsync(accounting 解冻过账+trade.markPaid)→outbox→settle.recordPay+notify.send→recon 核对。
**渠道支付**：同上+channel.PREAUTH→capture；异步回执丢失→payment 查询补单→channel 查询→收敛终态。
**退款**：payment.refund→checkRefundable→accounting.freeze(退款预留)+trade.refundMark→commit→channel.refund→手续费比例退→settle.recordRefund+notify→recon。
**充值/提现**：fundPay→trade.createOrder(TOPUP/WITHDRAW)+payment(fee=0)→accounting(SYS_CASH↔用户)→settle/notify→recon。
**担保交易**[升]：pay→资金入担保户→确认收货/超时→打款卖家(accounting 担保户→商户)→settle。
**分账**[升]：pay 成功→split 规则→accounting 多方过账(商户→接收方)→settle 分账明细→可回退。
**T+N 结算**[升]：日切→settle 出批次(净额)→提现到银行(channel 出金)→到账回执→商户账单。
**日切对账**[升]：XXL-Job 分片→recon 日切批量+渠道文件解析→差错→自动冲正/挂账/工单→报表。

---

## 6. 规则中心汇总（供 Apollo 配置下发）

| 类别 | 规则 | 默认 | 维度 |
|---|---|---|---|
| 限额 | 单笔上限 | 10,000,000 分 | 全局/用户/商户/卡/渠道[升] |
| 限频 | 滑动窗口 | 5 笔/10s | payerId |
| 累计限额[升] | 日/月/年 | 可配 | 用户/商户 |
| 费率 | 交易手续费 | 60bps | 商户阶梯[升] |
| 退款 | 手续费比例退+末笔退剩余 | 公式 | 支付单 |
| 结算 | T+N | T+1 | 商户 |
| 风控处置[升] | 评分阈值→放行/增强/拦截/人审 | 可配 | 全局 |
| 名单[升] | 黑/白/灰 | — | 用户/商户/卡/设备/IP |
| 免密[升] | 小额免密限额 | 可配 | 用户 |
| 对账 | 六项核对+差错分类 | 固定 | 全局 |
| 超时 | 订单关闭/担保自动确认 | 可配 | 交易产品 |

---

## 7. 管理端 RBAC 权限矩阵（角色×模块）

| 模块 | 超管 | 运营 | 财务 | 风控 | 客服 | 审计(只读) |
|---|---|---|---|---|---|---|
| 概览 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 交易/退款 | ✓ | ✓ | ✓ | ✗ | 查+发起 | 查 |
| 分账/清结算 | ✓ | ✗ | ✓ | ✗ | ✗ | 查 |
| 对账/差错 | ✓ | ✗ | ✓ | ✗ | ✗ | 查 |
| 风控/名单 | ✓ | ✗ | ✗ | ✓ | ✗ | 查 |
| 营销 | ✓ | ✓ | ✗ | ✗ | ✗ | 查 |
| 会员/进件/KYC | ✓ | ✓ | ✗ | ✗ | 查 | 查 |
| 账务/冲正/日切 | ✓ | ✗ | ✓ | ✗ | ✗ | 查 |
| 开放平台/密钥 | ✓ | ✗ | ✗ | ✗ | ✗ | 查 |
| 系统/RBAC/配置 | ✓ | ✗ | ✗ | ✗ | ✗ | 查 |

> 资金敏感操作(冲正/日切/大额退款/出金/密钥轮换)强制二次认证+双人复核(S-7)，全量审计。

---

## 8. 与 IMPLEMENTATION-PLAN 的衔接
本文 §2 的流程/逻辑/规则 = 任务卡的「业务验收语义」；§3 = 基础设施任务卡；§4 = 管理端前端任务卡；§5/§6/§7 = 集成测试与配置/权限设计输入。代码级类/接口/DDL 见 IMPLEMENTATION-PLAN.md（编制中）。