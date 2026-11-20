# payment-pro 需求规格说明书（REQUIREMENTS）

> 版本 2.0.0 · 2026-11-20 · **交付态文档**（描述已实现并验收的系统，非计划态）
> 文档链：**REQUIREMENTS**（本文：需求与验收）→ **ARCHITECTURE**（架构与不变量）→ **DESIGN**（详细设计/字段级 DDL/代码走读）→ **IMPLEMENTATION-PLAN**（89 卡任务台账）
> 金额一律 `long`「分」。标注：`[已交付]` = 已实现且有验收证据；`[部署必改]` = 生产上线前必须调整；`[未纳入]` = 明确不做。

---

## 1. 系统定位与边界

### 1.1 定位
payment-pro 是**独立支付平台**，为业务方（首个接入方：ride-pro 打车系统）提供账户、账务、交易、支付、收银台、渠道、风控、清结算、通知、对账、营销抵扣的完整能力，并以 **OpenAPI + 管理台**两种面暴露。

### 1.2 边界（已拍板，硬约束）
| 边界 | 约定 |
|---|---|
| **不自建 C 端页面** | 平台不提供收银台/钱包/账单等 C 端 H5。收银台能力以 `cashier.*` OpenAPI 开放，**由业务方在自己的 App 内渲染**（ride-pro 乘客端已落地，视觉对标支付宝） |
| **不迁 Seata** | 保留自研 `tcc-core`（XTS 风格 TCC），传输无关 SPI |
| **不迁 Nacos** | 保留 ZooKeeper 作注册中心 |
| **运营能力必须进管理台** | 任何 P1/P2 运营能力不得只有 API 面；管理台菜单按业务域**两级分类** |
| **数据保护** | `data/` 与 H2/MySQL 的删除/重置须用户明确确认；分片只 `CREATE + INSERT` 复制、原行保留、不下线单库；清残留走**红冲 + 双人复核 + 审计**，不改库 |

### 1.3 干系人
- **业务方（商户）**：经 OpenAPI 接入，持 appId/appSecret，按 scope 授权
- **平台运营**：经管理台（RBAC 四角色）做审核、复核、差错处置、配置、灰度
- **平台财务/审计**：试算平衡、总账报表、审计日志、备付金核对
- **接入方 C 端用户**：只与业务方 App 交互，不直接访问 payment-pro

---

## 2. 功能需求（分域）

### 2.1 会员域 member
| 编号 | 需求 | 状态 |
|---|---|---|
| M-1 | 商户/个人会员注册、查询、状态管理（ACTIVE/FROZEN/CLOSED） | [已交付] |
| M-2 | 商户进件与审核（资质材料、状态流转、时效看板） | [已交付] |
| M-3 | KYC 实名（身份证/营业执照校验、分级联动） | [已交付] |
| M-4 | 合同与结算卡、商户密钥与证书（RSA2 证书上传/轮换） | [已交付] |
| M-5 | C 端会员认证（注册/短信码/登录/登出/会话/改密），**平台只提供 API 与治理侧观测，不建 C 端页面** | [已交付] |
| M-6 | 绑卡管理（绑卡/解绑、卡号脱敏、signNo） | [已交付] |

### 2.2 账户域 account
| 编号 | 需求 | 状态 |
|---|---|---|
| A-1 | 开户（BALANCE/授信等类型）、余额与可用额查询 | [已交付] |
| A-2 | 账户分级限额 I/II/III/IV，升级须 KYC 前置强校验，限额试算只读 | [已交付] |
| A-3 | 冻结细分四原因（风控/司法/业务/争议），解冻须对应原因，司法冻结双人 | [已交付] |
| A-4 | 账户生命周期五重护栏（销户/降级/状态变更留痕、前置校验） | [已交付] |
| A-5 | 多币种账户与汇率（按币种试算） | [已交付] |

### 2.3 账务域 accounting
| 编号 | 需求 | 状态 |
|---|---|---|
| AC-1 | **复式记账**：每笔业务借贷必相等；`balance = credit − debit`，`available = balance − frozen` | [已交付] |
| AC-2 | 科目体系与科目映射（含 2001 客户余额、2006 保证金、6001 手续费收入、1001 外部现金） | [已交付] |
| AC-3 | 会计日切/日终报表（按币种） | [已交付] |
| AC-4 | 红冲（全额反向凭证，区别于业务退款）、挂账（SUSPENSE） | [已交付] |
| AC-5 | 试算平衡（D=C）、Σbalance=0、总账报表、备付金核对 | [已交付] |
| AC-6 | 系统户允许透支（SYS_CASH/SYS_FEE/SYS_DEPOSIT/SYS_ADVANCE），客户户严禁透支 | [已交付] |
| AC-7 | 保证金缴存/释放/扣划、垫资垫付/追偿，**全部经既有 post，不自建账本** | [已交付] |

### 2.4 交易域 trade
| 编号 | 需求 | 状态 |
|---|---|---|
| T-1 | 交易单创建（`merchant + outTradeNo` 幂等）、担保确认 | [已交付] |
| T-2 | 预授权（冻结/完成扣款/撤销） | [已交付] |
| T-3 | 交易产品目录与状态机（DynamicConfig 热覆盖） | [已交付] |
| T-4 | 超时关闭留痕与重开 | [已交付] |
| T-5 | **合并支付**（Σ子订单=合并额、fee 分摊守恒、部分失败整体回滚、拆分个别支付）+ **分阶段支付**（Σ期额=订单额、顺序期 CAS、各期幂等、期撤销） | [已交付] |
| T-6 | **撤销 vs 退款边界**：当日未清算且全额未退 → 撤销（CX 反向凭证、无退款单）；已清算/已退/CARD → 走退款。**未支付改价**（original_amount 首次留痕、计数累加、不破坏 outTradeNo 幂等）+ **已支付差额补退** | [已交付] |

### 2.5 支付核心 payment
| 编号 | 需求 | 状态 |
|---|---|---|
| P-1 | 支付受理（BALANCE/CARD）、TCC 编排、终态收敛 | [已交付] |
| P-2 | **组合支付 COMBO**（多腿、Σ腿=总额、每腿独立账务分支、退款按腿水位分摊） | [已交付] |
| P-3 | 退款（部分退/多次退、累计可退校验、杜绝超退） | [已交付] |
| P-4 | 退款异步化（CARD 单腿 + 渠道异步开关；缺省与改造前逐字节一致） | [已交付] |
| P-5 | 撤销竞态守卫（落单后复读状态，非 SUCCESS 即失败关闭不留悬挂） | [已交付] |
| P-6 | 大额复核（阈值触发 → A0428 + reviewId，双人审批后才动账） | [已交付] |
| P-7 | 营销抵扣（券/红包/积分/活动预算），随 TCC 回滚退回，崩溃窗口由作业兜底 | [已交付] |
| P-8 | **MERGE_COMBO 叠加**（合并 × 组合，两层守恒） | [已交付] |

### 2.6 收银台 cashier
| 编号 | 需求 | 状态 |
|---|---|---|
| C-1 | 支付方式渲染（按付款账户可用方式 + 免密限额） | [已交付] |
| C-2 | 付款码（USER_PRESENTED / MERCHANT_PRESENTED，每分钟刷新、一次性核销、防重放） | [已交付] |
| C-3 | 支付验证四型（PASSWORD / SMS / BIO / SMALL_AMOUNT_FREE），失败不受理不扣款 | [已交付] |
| C-4 | 验证前置受理 `payVerified` / 扫码核销 `scanPay`（幂等 requestId） | [已交付] |
| C-5 | **能力以 OpenAPI 开放给业务方自渲染**，平台不建 C 端页面 | [已交付] |

### 2.7 渠道网关 channel
| 编号 | 需求 | 状态 |
|---|---|---|
| CH-1 | 异步回执 + 补单收敛 + TCC WAITING；渠道状态未知只等待，绝不臆断失败 | [已交付] |
| CH-2 | 模拟银行渠道（沙箱：回执丢失注入、尾号 0000 拒绝）、渠道路由与限额/维护窗 | [已交付] |
| CH-3 | 渠道对账（状态不符转人工，不自动动账） | [已交付] |
| CH-4 | 签约代扣 `channel.sign`、`channel.agent.pay` | [已交付] |
| CH-5 | 网银渠道 | [未纳入] 管理台如实标注「即将上线」 |

### 2.8 风控域 risk
| 编号 | 需求 | 状态 |
|---|---|---|
| R-1 | 单笔/日累计限额、频率闸（**每 payer 10s 内 5 笔**，可配） | [已交付] |
| R-2 | 名单、设备/IP/时间维度规则 | [已交付] |
| R-3 | 多维限额与账户分级联动 | [已交付] |
| R-4 | 风险评分（FREQ25/AMOUNT_DEV20/LIST30/DEVICE25/IP20/TIME10）+ 分级处置：<40 PASS / 40-59 CHALLENGE / 60-79 LIMIT / 80-94 REJECT / ≥95 MANUAL_REVIEW → 差错单（不动账） | [已交付] |

### 2.9 清结算域 settle
| 编号 | 需求 | 状态 |
|---|---|---|
| ST-1 | T+N 结算周期、结算单 | [已交付] |
| ST-2 | 分账（比例/固定、守恒校验、退回） | [已交付] |
| ST-3 | 清算跑批（按会计日、分片并行、force 重跑） | [已交付] |
| ST-4 | 手续费口径（fee bps 可配，默认 60bps，half-up） | [已交付] |
| ST-5 | 提现（状态机 + 冻结余额 + 渠道出金） | [已交付] |
| ST-6 | 保证金/垫资（见 AC-7） | [已交付] |

### 2.10 通知域 notify
| 编号 | 需求 | 状态 |
|---|---|---|
| N-1 | 事件通知（PAY_SUCCESS/REFUND_SUCCESS 等），签名 + 重试 + DEAD | [已交付] |
| N-2 | 通知签名口径与商户密钥联动 | [已交付] |
| N-3 | 事件类型与模板（NotifyEvent 枚举为单一事实来源，服务端强校验） | [已交付] |
| N-4 | 查询与重发（服务端分页 + 脱敏，**重发恒需二次认证**） | [已交付] |

### 2.11 对账域 recon
| 编号 | 需求 | 状态 |
|---|---|---|
| RC-1 | 六项对账（支付-账务、支付-交易、渠道、备付金、结算、挂账），语义违规生成差错单 | [已交付] |
| RC-2 | 对账收敛与差错平台联动 | [已交付] |
| RC-3 | 分片影子双跑逐行逐值比对（diffTotal=0） | [已交付] |

### 2.12 营销域 marketing（可选第 14 进程）
| 编号 | 需求 | 状态 |
|---|---|---|
| X-1 | 券/红包/积分/活动预算、最优组合试算 | [已交付] |
| X-2 | 抵扣资金反向与目标维度重分摊 | [已交付] |
| X-3 | 营销回滚作业（TCC 回滚后兜底） | [已交付] |

### 2.13 开放网关 gateway
| 编号 | 需求 | 状态 |
|---|---|---|
| G-1 | OpenAPI HMAC-SHA256 签名（appId/sign/timestamp **毫秒**/TreeMap 排序 `k=v&`、±300s 窗口、**nonce 可选**） | [已交付] |
| G-2 | springdoc 契约（免鉴权、生成期脱敏）+ 版本兼容策略（v1 冻结面逐字节不变，新增一律 additive） | [已交付] |
| G-3 | 错误码表（单一事实来源 `/api/errors`） | [已交付] |
| G-4 | 应用与授权（appId/scope/配额/计费）、沙箱、配额与计费、版本与 SDK | [已交付] |
| G-5 | HTTPS 8443 + 自签证书 | [已交付] |

### 2.14 管理台（运营面）
| 编号 | 需求 | 状态 |
|---|---|---|
| U-1 | **12 业务分组两级导航**，69 个二级菜单项，hash 路由、权限隐藏、rail 折叠 | [已交付] |
| U-2 | **22 个原待建页全部建成真实页面**（列表+筛选+分页+详情抽屉+空/加载/错误态），浏览器逐页实测 22/22 | [已交付] |
| U-3 | RBAC 四角色 ADMIN(`*`)/OPERATOR/AUDITOR/READONLY，权限点细到模块读写 | [已交付] |
| U-4 | 二次认证 step-up（**再验当前登录账号自己的口令**，300s 令牌，`X-Step-Up-Token`） | [已交付] |
| U-5 | 双人复核（提交人/审批人恒取会话用户，冒充 403） | [已交付] |
| U-6 | 审计日志、差错平台、备付金、配置中心、灰度、可观测、调度作业、限流熔断、分片影子比对 | [已交付] |
| U-7 | 单据与凭证/流水**关联跳转** | [已交付] |

---

## 3. 非功能需求

### 3.1 资金安全（最高优先级）
| 编号 | 需求 | 验收证据 |
|---|---|---|
| NF-1 | 借贷必相等、Σbalance=0、试算 D=C | 试算 `totalDebit=totalCredit=3,330,946,497`、`balanced=true`、`balanceSum=0` |
| NF-2 | 客户户严禁透支；系统户可透支 | 余额守卫 SQL `balance-frozen_amount>=amount` |
| NF-3 | **零悬挂**：任何失败出口都必须回滚/重开，不留 PROCESSING/PAYING 悬挂 | 竞态三轮 + 「本批支付单全部终态」「本批订单零 PAYING 悬挂」断言 |
| NF-4 | 拿到回执号 ≠ 钱已划转，**须校验对端终态**才本地确认 | ride `requireSettled` 守卫 + 3 个定向单测 |
| NF-5 | 幂等：确定性 bizNo/requestId + CAS 行数检查 + 唯一键 | 8 并发×3 轮只 1 单；幂等复提 repeated=true |
| NF-6 | 敏感操作二次认证 + 大额双人复核，**绝不可绕过** | fund-guard 37/37（含 3 条冒充拒绝断言） |
| NF-7 | 拒绝留痕分级：容量保护可受理前拒绝；**业务失败须留 FAILED 痕** | 渠道宕机口径回归已修 |
| NF-8 | 历史差错只走**红冲 + 双人 + 审计**，不改不删原行 | CURTEST 红冲 RV7883、双账本冲正 RV8504 + 4 笔冲正腿 |

### 3.2 一致性与可靠性
| 编号 | 需求 |
|---|---|
| NF-9 | **outbox 是唯一事实来源**；RocketMQ 事务消息（半消息+回查）为主投递，3s 轮询兜底**永不删除** |
| NF-10 | DB 异常返回 UNKNOWN，**不臆断 ROLLBACK**；回查只写 additive 元数据 |
| NF-11 | 补单收敛：渠道状态未知只等待；DEAD/查询耗尽只告警转人工 |
| NF-12 | 跨系统双账本：业务方余额 = 开户基线快照 + 已确认净额；**周期对账不得每轮逐条远程查对端**（缓存 + 按热点限流阈值节流 + 未补全显式跳过） |
| NF-13 | 一致性保证放**服务端**，前端补偿只管展示 |

### 3.3 性能与容量
| 编号 | 需求 |
|---|---|
| NF-14 | 长耗时 ops 端点用**方法级超时**，不整体放宽（清算跑批实测 10.9s → `runSettleBatch` timeout=120s） |
| NF-15 | 限流拒绝发生在**受理前**，不落单、不留 PROCESSING 悬挂 |
| NF-16 | 分库分表：单库分片（settle_record/recon_report/payment/refund/account_balance/ledger_entry/trade_order），协调表与索引表全 SINGLE |

### 3.4 安全
| 编号 | 需求 |
|---|---|
| NF-17 | 口令/密钥/appSecret **绝不回显、绝不落库明文**；appSecret 仅注册响应返回一次 |
| NF-18 | 敏感配置放仓库外 `~/.config/<app>/`（600），仓库只留 example |
| NF-19 | 卡号/身份证/手机号脱敏；C 端认证只存一次性 verifyToken |
| NF-20 | 管理台 Bearer STATELESS + 登录限流（5 次失败 → 429）+ `X-Frame-Options: DENY` |

### 3.5 可观测与运维
| 编号 | 需求 |
|---|---|
| NF-21 | 全栈健康探活（13/13）、Micrometer/Prometheus 指标、告警判定 |
| NF-22 | Apollo 配置中心热更（**停配置中心支付仍成功**、热更不重启生效） |
| NF-23 | XXL-Job 托管 + 本地 `@Scheduled` 兜底 + ShedLock 跨实例互斥，**逐作业三重降级回切** |
| NF-24 | Sentinel QPS + appId 热点参数限流 + 异常数熔断（A0429/B0003 规范错误体） |
| NF-25 | 双 profile：**local 零依赖**（H2 + 内嵌 ZK）/ **docker**（共享 MySQL pp_* + ZK 2181 + RocketMQ + Redis + Apollo） |

---

## 4. 对外契约需求（ride-pro 接入）

### 4.1 v1 冻结面（8 个方法，逐字节不变）
`trade.create` · `trade.pay` · `trade.query` · `account.open` · `account.balance` · `account.transfer` · `fund.topup` · `fund.withdraw`

**验收门禁**：`ride-pro/verify-integration.py` **24/24**，含 md5 + 方法集双证、F 段停 pp 重启混沌、pp 六项对账 pass。已通过 **6 次**。

### 4.2 additive 扩展面（11 个新方法）
`cashier.methods` · `cashier.code.create` · `cashier.code.parse` · `cashier.code.query` · `cashier.scanPay` · `cashier.verify` · `cashier.payVerified` · `cashier.payPassword.set` · `cashier.sms.send` · `channel.sign` · `channel.agent.pay`

**红线**：新增字段缺省 null，不得改变既有字段语义；BALANCE 同步受理语义不变；notify http 只 additive。

### 4.3 关键语义约定
| 项 | 约定 |
|---|---|
| 主扫 `qrContent` | 是 **URL** `https://cashier.payment-pro.example/scan?code=<token>&amount=<amt>`，消费方须按 `code=` 参数归一化 |
| 被扫 `qrContent` | 等于码值本身 |
| 受理终态 | BALANCE 同步受理 → **PROCESSING → SUCCESS**；消费方**必须校验终态**才可本地动账 |
| 手续费 | 两层独立：平台抽成（业务方自定 bps）与支付通道费（pp fee bps，默认 60bps）。**通道费由商户承担**，业务方净收入 = 抽成 − 通道费 |
| CARD 出资 | pp **不扣 BALANCE 户**（钱来自 SYS_CASH 外部现金）；业务方不得扣用户钱包 |
| 沙箱 | 沙箱请求网关截流，只走 `openapi_sandbox_*` 账本，**生产资金一行不动**；生产必关 |

---

## 5. 验收标准与证据

### 5.1 分期完成度
| 期 | 卡数 | 状态 |
|---|---|---|
| P0 生产化 | 23 | **23/23 全验收** |
| P1 核心支付能力 | 47 | **47/47 全验收** |
| P2 增值生态 | 19 | **17/19**（T-5/T-6 已验收；I-1 批准不做；I-6 待实现） |

### 5.2 专项验证脚本（全部可一键重跑）
`smoke 79` · `verify-pro 22` · `verify-channel 111` · `verify-risk 116` · `verify-member-account 109` · `verify-settle 158` · `verify-accounting 255` · `verify-notify-gw 149` · `verify-trade 131` · `verify-recon 83` · `verify-payment-orchestration 107` · `verify-cashier 199` · `verify-marketing 191` · `verify-marketing-orch 323+30` · `verify-openplatform 95` · `verify-multicurrency 134` · `verify-risk-plus 177` · `verify-sign 183`（docker+local）· `verify-account-lifecycle 196` · `verify-multiclient 148` · `verify-sharding 123` · `verify-sharding-fund 253` · `verify-mq 55` · `verify-config 47` · `verify-job 88` · `verify-sentinel 42` · `verify-metrics 96` · `verify-dispute 73` · `verify-fund-guard 37+8` · `verify-rbac 51` · `verify-secret 32` · `verify-reverse 16` · `verify-suspense 28` · `verify-notify-sign 20` · `verify-reconverge 26` · **`verify-trade-plus`（T-5/T-6，10 段）**

一键总验收：`./accept-all.sh`（phase0 稳定性门禁 → phase1 停 ride 跑 smoke+verify-pro → phase2 全专项 → phase3 ride 24/24 兼容门禁）。

### 5.3 管理台验收
22 个原待建页**真实浏览器逐页实测 22/22 通过**（最多一页 14 表 124 行），零占位文案，空态均带原因。

---

## 6. 生产部署必改项 [部署必改]

1. **切流**：分片当前保持单库观察态（双写开、`initial-active=false`），切流须拍板
2. **ZK 集群化**（I-1，批准不做）：当前单节点，生产须换集群
3. **沙箱生产必关**
4. **appM1 免费不限**仅联调口径，生产须配配额与计费
5. **自签证书**换正式 CA 证书
6. **默认口令**（`PP_ADMIN_PASSWORD` 缺省 admin123）必须改；注意**改环境变量不会更新已存在 admin 的口令**
7. **Vault** 当前缺失自动降级本地文件实现，生产须接真 Vault
8. **XXL accessToken** 须与调度中心一致（跨系统副作用见 COLLABORATION）
9. **共享中间件**（MySQL/ZK/RocketMQ/Redis）当前与 ops-pro/claim 共用，生产须隔离
10. **会计日不回正**、**结转关闭**为已拍板口径，上线前须复核是否符合财务要求

---

## 7. 明确不做 [未纳入]
- 平台自建 C 端 H5（改开放 OpenAPI）
- 迁移 Seata / Nacos
- I-1 ZK 集群化（列入部署必改）
- 网银渠道 CH-5、授信账户 A-1 授信部分、快捷支付签约（管理台如实标注「预留/即将上线」，**不假实现**）