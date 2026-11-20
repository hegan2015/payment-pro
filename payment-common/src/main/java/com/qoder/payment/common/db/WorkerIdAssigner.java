package com.qoder.payment.common.db;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * I-5a workerId 分配（避免跨实例重号，E-7 前置）。优先级：
 * <ol>
 *   <li><b>显式配置</b> {@code payment.idgen.worker-id}（0..1023）：多机部署可手工钉死；</li>
 *   <li><b>ZooKeeper 临时节点抢占</b>：在 {@code /payment-pro/idgen/workers/<slot>} 逐个尝试
 *       {@code EPHEMERAL} 建节点，建成即独占该 slot（节点名即 workerId，天然无重号、进程退出自动释放）。
 *       ZK 是双 profile 既有依赖（local 内嵌 / docker 共享 2181），不新增中间件；</li>
 *   <li><b>端口兜底</b>：{@code server.port % 1024}（本机 13 进程 actuator 端口 18081-18092 互不相同），
 *       ZK 不可达时仍可发号且单机不撞号，日志 WARN 提示。</li>
 * </ol>
 *
 * <p>沿用 Dubbo 的 {@code dubbo.registry.address}（zookeeper://host:port）作为连接串，不新增配置面。
 */
public final class WorkerIdAssigner {

    private static final Logger log = LoggerFactory.getLogger(WorkerIdAssigner.class);

    private static final int MAX_WORKER = 1023;
    private static final String DEFAULT_PATH = "/payment-pro/idgen/workers";

    /** 持有客户端以保持 ZK 会话（临时节点存活 = slot 独占）。 */
    private static CuratorFramework holder;

    private final IdGenerator owner;
    private final int configured;
    private final String zkAddress;
    private final String basePath;
    private final int fallbackPort;
    private final String instanceKey;

    public WorkerIdAssigner(IdGenerator owner, int configured, String zkAddress, String basePath,
                            int fallbackPort, String instanceKey) {
        this.owner = owner;
        this.configured = configured;
        this.zkAddress = zkAddress;
        this.basePath = basePath == null || basePath.isBlank() ? DEFAULT_PATH : basePath;
        this.fallbackPort = fallbackPort;
        this.instanceKey = instanceKey;
    }

    public int assign() {
        if (configured >= 0) {
            if (configured > MAX_WORKER) {
                throw new IllegalStateException("payment.idgen.worker-id 越界（0.." + MAX_WORKER + "）: " + configured);
            }
            owner.workerIdSource("CONFIG");
            log.info("I-5a 全局发号 workerId={}（来源=显式配置）", configured);
            return configured;
        }
        Integer zk = claimFromZookeeper();
        if (zk != null) {
            owner.workerIdSource("ZOOKEEPER");
            log.info("I-5a 全局发号 workerId={}（来源=ZK 临时节点 {}/{}）", zk, basePath, zk);
            return zk;
        }
        int port = fallbackPort > 0 ? fallbackPort % (MAX_WORKER + 1) : Math.abs(instanceKey.hashCode()) % (MAX_WORKER + 1);
        owner.workerIdSource("PORT_FALLBACK");
        log.warn("I-5a ZK 不可达，workerId 端口兜底={}（instance={}）；多机部署请显式配置 payment.idgen.worker-id", port, instanceKey);
        return port;
    }

    private Integer claimFromZookeeper() {
        if (zkAddress == null || zkAddress.isBlank()) {
            return null;
        }
        try {
            CuratorFramework client = CuratorFrameworkFactory.newClient(zkAddress,
                    new ExponentialBackoffRetry(500, 3));
            client.start();
            if (!client.blockUntilConnected(8, TimeUnit.SECONDS)) {
                client.close();
                log.warn("I-5a workerId 申请：ZK {} 8s 未连通", zkAddress);
                return null;
            }
            try {
                client.create().creatingParentsIfNeeded().forPath(basePath);
            } catch (KeeperException.NodeExistsException ignored) {
                // 父节点已存在：正常
            }
            byte[] payload = instanceKey.getBytes(StandardCharsets.UTF_8);
            for (int slot = 0; slot <= MAX_WORKER; slot++) {
                try {
                    client.create().withMode(CreateMode.EPHEMERAL).forPath(basePath + "/" + slot, payload);
                    synchronized (WorkerIdAssigner.class) {
                        holder = client;
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            try {
                                client.close();
                            } catch (Exception ignored) {
                                // 进程退出释放临时节点
                            }
                        }, "pp-idgen-zk-close"));
                    }
                    return slot;
                } catch (KeeperException.NodeExistsException occupied) {
                    // slot 被其它存活实例占用，试下一个
                }
            }
            client.close();
            log.error("I-5a workerId 申请：1024 个 slot 全被占用");
            return null;
        } catch (Throwable e) {
            // Curator 缺失 / ZK 异常 → 端口兜底（不阻断启动，发号仍不重号）
            log.warn("I-5a workerId 申请失败（{}），转端口兜底", e.toString());
            return null;
        }
    }
}