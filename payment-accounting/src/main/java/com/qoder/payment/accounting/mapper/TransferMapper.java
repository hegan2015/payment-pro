package com.qoder.payment.accounting.mapper;

import com.qoder.payment.accounting.dataobject.TransferDO;
import com.qoder.payment.facade.accounting.VoucherQuery;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 转账凭证（transfer_record）。
 *
 * <p>S-6 凭证不可篡改：本表只追加（INSERT），禁止 UPDATE lines——
 * 冲正/挂账只能追加新凭证，护栏见 {@code VoucherGuard}。
 *
 * <p>AC-3 增 biz_date（additive 列，落库后同样只读）；AC-4 增只读分页联查。
 * 本表 <b>不分片</b>（凭证全局唯一、只追加）。
 */
@Mapper
public interface TransferMapper {

    @Insert("INSERT INTO transfer_record(biz_no,`lines`,subject,ref_id,reversed,voucher_type,created_at,biz_date,"
            + "currency,exchange_rate,rate_scale,base_amount) "
            + "VALUES(#{bizNo},#{lines},#{subject},#{refId},#{reversed},#{voucherType},#{createdAt},#{bizDate},"
            + "#{currency},#{exchangeRate},#{rateScale},#{baseAmount})")
    int insert(TransferDO transfer);

    @Select("SELECT * FROM transfer_record WHERE biz_no=#{bizNo}")
    TransferDO selectByBizNo(String bizNo);

    @Select("SELECT * FROM transfer_record WHERE ref_id=#{origBizNo} AND biz_no LIKE 'RV%'")
    TransferDO selectReverseByOrig(String origBizNo);

    // ---------------- AC-4 凭证联查（只读） ----------------

    String VOUCHER_FILTER = "<where>"
            + "<if test='q.bizNo != null and q.bizNo != \"\"'> AND biz_no = #{q.bizNo}</if>"
            + "<if test='q.refId != null and q.refId != \"\"'> AND ref_id = #{q.refId}</if>"
            + "<if test='q.voucherType != null and q.voucherType != \"\"'> AND voucher_type = #{q.voucherType}</if>"
            + "<if test='q.bizDate != null'> AND biz_date = #{q.bizDate}</if>"
            + "<if test='q.dateFrom != null'> AND created_at &gt;= #{q.dateFrom}</if>"
            + "<if test='q.dateTo != null'> AND created_at &lt; #{q.dateTo}</if>"
            + "<if test='bizNos != null and bizNos.size() > 0'> AND biz_no IN "
            + "<foreach item='n' collection='bizNos' open='(' separator=',' close=')'>#{n}</foreach></if>"
            + "</where>";

    @Select("<script>SELECT * FROM transfer_record " + VOUCHER_FILTER
            + " ORDER BY created_at DESC, biz_no DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TransferDO> selectVoucherPage(@Param("q") VoucherQuery q, @Param("bizNos") List<String> bizNos,
                                       @Param("size") int size, @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM transfer_record " + VOUCHER_FILTER + "</script>")
    long countVoucherPage(@Param("q") VoucherQuery q, @Param("bizNos") List<String> bizNos);

    @Select("SELECT COUNT(*) FROM transfer_record")
    int count();
}