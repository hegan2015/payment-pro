package com.qoder.payment.recon.mapper;

import com.qoder.payment.recon.dataobject.DisputeFixLogDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DisputeFixLogMapper {

    @Select("SELECT next_value_for('seq_dispute_fix')")
    long nextId();

    @Insert("INSERT INTO dispute_fix_log(id,dispute_id,action,operator,before_status,after_status,detail,created_at) "
            + "VALUES(#{id},#{disputeId},#{action},#{operator},#{beforeStatus},#{afterStatus},#{detail},#{createdAt})")
    int insert(DisputeFixLogDO log);

    @Select("SELECT * FROM dispute_fix_log WHERE dispute_id=#{disputeId} ORDER BY id")
    List<DisputeFixLogDO> selectByDispute(@Param("disputeId") long disputeId);
}