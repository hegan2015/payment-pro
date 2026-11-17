package com.qoder.tcc;

/** 全局事务到达终态时的回调，与终态落库在同一本地事务内执行（用于事务性 outbox）。 */
public interface TccCompletionListener {

    void onConfirmed(String xid);

    void onCancelled(String xid, String reason);
}