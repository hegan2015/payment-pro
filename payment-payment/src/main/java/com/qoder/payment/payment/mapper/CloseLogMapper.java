package com.qoder.payment.payment.mapper;

import com.qoder.payment.payment.dataobject.CloseLogDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CloseLogMapper {

    @Select("SELECT next_value_for('seq_close_log')")
    long nextId();

    @Insert("INSERT INTO payment_close_log(id,payment_id,type,request_id,biz_no,created_at) "
            + "VALUES(#{id},#{paymentId},#{type},#{requestId},#{bizNo},#{createdAt})")
    int insert(CloseLogDO log);

    @Select("SELECT * FROM payment_close_log WHERE payment_id=#{paymentId} AND type=#{type}")
    CloseLogDO selectByPaymentAndType(String paymentId, String type);
}