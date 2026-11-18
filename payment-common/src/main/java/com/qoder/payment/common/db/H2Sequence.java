package com.qoder.payment.common.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * H2 序列取号 alias，供本地 H2 profile 与 MySQL 存储函数 next_value_for 保持同一 mapper SQL。
 * H2 通过 CREATE ALIAS next_value_for FOR "...H2Sequence.next" 注册，首参 Connection 由 H2 注入。
 */
public final class H2Sequence {

    public static long next(Connection conn, String seq) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT NEXT VALUE FOR " + seq)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private H2Sequence() {
    }
}