package com.qoder.tcc;

/**
 * TCC 参与者 SPI（传输无关）。协调器按 participant 名路由到本接口实现，
 * 三个方法均须满足幂等；失败直接抛异常（不包装 RPC 返回体），
 * 由协调器捕获后驱动全局回滚或留给补偿调度重试。
 *
 * <p>接入方可用任意传输方式实现本接口：本地调用、Dubbo、HTTP 等。
 */
public interface TccParticipant {

    /** 一阶段：预留资源。失败则协调器发起全局 Cancel。 */
    void tryAction(TccBranchRequest request);

    /** 二阶段提交：使用预留资源完成业务。必须幂等、可重试。 */
    void confirm(TccBranchRequest request);

    /** 二阶段回滚：释放预留资源。须处理空回滚与悬挂。 */
    void cancel(TccBranchRequest request);
}