package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.TransferDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TransferMapper {

    @Insert("INSERT INTO transfer_record(biz_no,`lines`,subject,ref_id,reversed,created_at) "
            + "VALUES(#{bizNo},#{lines},#{subject},#{refId},#{reversed},#{createdAt})")
    int insert(TransferDO transfer);

    @Select("SELECT * FROM transfer_record WHERE biz_no=#{bizNo}")
    TransferDO selectByBizNo(String bizNo);
}