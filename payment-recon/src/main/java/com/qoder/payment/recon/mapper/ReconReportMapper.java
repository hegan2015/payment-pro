package com.qoder.payment.recon.mapper;

import com.qoder.payment.recon.dataobject.ReconReportDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReconReportMapper {

    @Select("SELECT NEXT VALUE FOR seq_recon")
    long nextId();

    @Insert("INSERT INTO recon_report(id,checked_at,pass,report) VALUES(#{id},#{checkedAt},#{pass},#{report})")
    int insert(ReconReportDO report);

    @Select("SELECT * FROM recon_report ORDER BY checked_at DESC, id DESC LIMIT 1")
    ReconReportDO selectLatest();
}