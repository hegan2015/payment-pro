# payment-pro 协作指南 —— 用户指导 AI 开发要点复盘

> 整理日期：2026-11-18 ｜ 来源：跨会话真实协作记录复盘
> 用途：新会话开工前读本文件，快速对齐用户的工作方式与本项目已拍板的决策，避免重复试探和回退。

## 一、项目定位与硬约束

- 参考**支付宝**领域划分的工业级支付系统：Java 17 + Spring Boot 3.2 + Maven 16 模块 + Dubbo + 自研 XTS 风格 TCC + 事务性 Outbox + H2 文件库 + 内嵌 ZooKeeper，13 进程。
- 演进脉络：payment-system（纯 JDK 单体）→ payment-ms（纯 JDK 微服务）→ payment-pro（工业级）→ tcc-core 通用组件抽出。
- **零外部依赖硬约束**：用户明确选定自研 TCC（不用 Seata）、H2 嵌入式 + 内嵌 ZK（不装外部 MySQL/ZK）；不引入 MQ 等需独立部署的组件。
- **数据神圣**：停服务可以，删除/重置 data/ 与 H2 库必须先经用户明确确认。
- 端口：8080（gateway）/8443（OpenAPI HTTPS）/2181（ZK）/8092（ZK HTTP）/2088x（Dubbo），与 ride-pro 完全错开可同跑。
- 内嵌 registry 自动启动**保留**：用户曾问能否关掉，经说明其余 12 进程全依赖它后选择「保持现状」。

## 二、关键指导决策（已拍板，勿回退）

1. **起点定调**（2026-11-15）：「用 Java 做一套支付系统，可以直接运行」——开箱即用、零依赖成为此后所有项目的硬约束；「代码像阿里员工水平」、Maven 化、Dubbo、工业级幂等/异步补偿。
2. **TCC 组件化**（2026-11-17，用户指令「把 tcc 抽成组件」）：选定通用化路线——tcc-core（com.qoder.tcc，13 类，零业务耦合，仅依赖 spring-jdbc/tx/autoconfigure/jackson/slf4j/lombok）；payment-dtx 变 7 类薄适配（Dubbo SPI 桥接 + 错误码映射 + DTO 转换）；六业务模块仅机械换 import，Dubbo 契约零改动。回归 smoke 79/79 + verify-pro 22/22 + SIGSTOP 混沌 9/9。
3. **双账本对接**（2026-11-17，用户指令「双账本模式帮我改造，通过 https 的 api 对接」）：在「钱包全托管 vs 双账本」中拍板**双账本**——ride-pro 本地权威 + mirror_outbox 镜像到本项目 OpenAPI（HTTPS 8443 自签证书 + HMAC，商户 appM52/M52/A56）。回调仅留痕加速展示，终态靠镜像+对账（服务端兜底偏好延续）。
4. **资金单升级**（v1.7.0，用户「充值提现 pay 不都应该有一笔交易与支付单吗」+「pay 查询流水可以查询总的并且要分页」）：选**完整重构非轻量展示**——充值/提现走完整 TCC 生成交易单+支付单（fund.topup/fund.withdraw，SYS_FUND 系统资金商户），账务流水跨账户全局分页（EntryQuery/PageDTO，size 钳 1..200）。ReconService 零改动自然过对账。
5. **文档权威**：DESIGN.md 为准，README.md 是其映射副本；DESIGN 更新后需手动重新同步 README。
6. **git 听指令**：「两个都提交一下」「给我提交 git」「push」——提交/推送均须明示；SSH 通道 `git@github.com:hegan2015/payment-pro.git`。

## 三、纠正与教训（用户纠正 → 规则）

| 事件 | 确立的规则 |
|---|---|
| init 提交把 data/logs/target/.idea 全部入库 | 运行时/构建产物不入库；.gitignore + `git rm --cached` 清理（磁盘文件保留） |
| 用户连续追问 H2/进程/ZK/日志与框架装配 | 操作类给可复制命令+实测；概念类通俗解释+file:line；机制类讲完整链路+定义处 |
| trade.create 无幂等，客户端重试造孤儿 CREATED 单（O88–O97） | OpenAPI 与控制台包装结构不同（OrderDTO 直返 vs {order}），解析失败重试需防孤儿单 |
| 提现镜像额受 pp 侧余额约束 | 接入前历史收入未镜像，超额提现镜像会 DEAD——对账用增量口径（基线快照+净额），绝对余额比对是错的 |
| 风控同付款方 5 笔/10s | 镜像 burst 靠退避重试消化，勿误判为故障 |
| 迟到 Try/Cancel 并发竞态抛 DuplicateKeyException | 原框架固有行为，recover 按设计自愈，勿误判为组件化引入的 bug |

## 四、通用协作原则（四系统一致）

1. **开箱即用**：零配置、一键启动脚本，交付前实际编译+测试+启动+UI 验证。
2. **方案拍板制**：给权衡选项（含推荐与理由）由用户拍板升级幅度，不擅自上重方案。
3. **数据保护**：删/重置数据先确认；验证尽量用隔离实例，演示库只留种子数据。
4. **密钥出仓库**：敏感配置放 `~/.config/<app>/`（700/600），仓库只留 .example；gitignore 不算安全。
5. **实测文化**：单测 + 冒烟 + 混沌（SIGSTOP/杀进程制造崩溃窗口）+ 浏览器全流程，全绿才算交付。
6. **设计类按商用标准**：架构/方案咨询默认生产级 + 主流正式框架（与构建类零依赖偏好区分）；确定性代码管判定与资金，LLM 只做理解/建议/解释。
7. **共享浏览器守则**：用户会实时操作内置浏览器；自动化用隐藏 iframe + localStorage 隔离 + no-store，不劫持用户页签；长等待拆两次调用（15s 超时）。
8. **中文交流 + 教学式回答**：操作类给可复制命令并实测演示；概念类通俗解释 + 代码 file:line；框架机制讲完整装配链路并指明定义处。
9. **git 听指令**：提交/推送均须用户明示；不 force push；不提交 data/logs/密钥。
10. **并行会话复查**：多会话同改一工作区，动手前先查现状。

## 五、指令模板与协作优化

**一行指令模板**：【构建/设计/运维】+ 目标 + 标准档位 + 完成定义 + 授权范围。
例：「构建，给 pp 加商户结算周期配置，零依赖，完成=单测+smoke+DESIGN 同步，提交推送一起做，删数据先问我。」

**已识别的优化方向**（2026-11-18 复盘）：
- 会话分工：一个会话只负责一个仓库，或开工声明范围；真并行用 worktree/分支隔离。
- 版本锚点：全量验证后打 git tag（如 pp-v1.7.0-verified）。
- README/DESIGN 同步自动化（脚本或 pre-commit 检查）。
- 测试数据与演示数据分离（推广 yiwu 的隔离实例模式）。
- 设计交付落盘成文档，不只留在对话里。
- 收掉脆弱依赖（正式 node 运行时、仓库可见性收尾、PAT 备选）。

## 六、运行与构建速查

- 构建：`JAVA_HOME=corretto-18`（release=17），`./mvnw clean package` 全量 reactor。
- 启停：`./run.sh`（首次 keytool 生成 config/payment-pro.p12 + 导出 cert.pem）/ `./stop.sh`。
- 验证：`smoke-test.py`（79 项）、`verify-pro.py`（22 项：并发幂等/订单竞态/渠道宕机补偿/全停重启持久化）。
- 双账本被接入方：ride-pro `./integrate-payment.sh`；OpenAPI 端点 trade.create/trade.pay/transfer/fund.topup/fund.withdraw/trade.query。
- 遗留待修：全链路 traceId 首跳为空（gateway/payment 受理/account 开立入口 MDC 未落，TraceFilter 已有 SPI 注册）。