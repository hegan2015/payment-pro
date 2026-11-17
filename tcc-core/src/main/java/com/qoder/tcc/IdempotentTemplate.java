package com.qoder.tcc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 工业级幂等模板（本地幂等表，先插占位再执行业务）。
 *
 * <p>占位插入、业务动作、结果回填在同一本地事务内：并发重复请求在唯一键上阻塞，
 * 待首请求提交后命中既有结果直接返回，天然防重且结果一致。
 */
@Slf4j
public class IdempotentTemplate {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IdempotentTemplate(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public <T> T execute(String bizType, String bizNo, Class<T> resultType, Supplier<T> action) {
        return tx.execute(s -> {
            try {
                jdbc.update("INSERT INTO idempotent_record(biz_type,biz_no,result,created_at) VALUES(?,?,NULL,?)",
                        bizType, bizNo, System.currentTimeMillis());
            } catch (DuplicateKeyException e) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT result FROM idempotent_record WHERE biz_type=? AND biz_no=?", bizType, bizNo);
                String json = rows.isEmpty() ? null : (String) rows.get(0).get("result");
                if (json == null) {
                    throw new TccException(TccException.CONFLICT, "幂等请求处理中: " + bizType + "/" + bizNo);
                }
                log.info("幂等命中 bizType={} bizNo={}", bizType, bizNo);
                return TccContextUtil.fromJson(json, resultType);
            }
            T result = action.get();
            jdbc.update("UPDATE idempotent_record SET result=? WHERE biz_type=? AND biz_no=?",
                    TccContextUtil.toJson(result), bizType, bizNo);
            return result;
        });
    }
}