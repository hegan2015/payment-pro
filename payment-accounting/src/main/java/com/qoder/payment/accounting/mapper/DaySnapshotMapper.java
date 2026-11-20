package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.DaySnapshotDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * AC-3 在途 TCC 会计日快照（<b>不分片</b>：与 transfer_record / idempotent_record 同为全局单表，
 * 保证跨日切时凭证归属受理日，且与业务写同库同事务）。只追加。
 */
@Mapper
public interface DaySnapshotMapper {

    @Insert("INSERT INTO accounting_day_snapshot(biz_no,biz_date,created_at) "
            + "VALUES(#{bizNo},#{bizDate},#{createdAt})")
    int insert(DaySnapshotDO snapshot);

    @Select("SELECT * FROM accounting_day_snapshot WHERE biz_no=#{bizNo}")
    DaySnapshotDO select(String bizNo);

    @Select("SELECT COUNT(*) FROM accounting_day_snapshot")
    int count();
}