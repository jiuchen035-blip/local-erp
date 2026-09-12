package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 记账凭证 */
@Data
@TableName("voucher")
public class Voucher {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String voucherNo;    // 记-0001
    private String voucherDate;  // yyyy-MM-dd
    private String sourceType;   // BILL/MANUAL/OPENING/CLOSE
    private Long sourceId;       // 关联单据id
    private String summary;
    private Double totalAmount;
    private String createdBy;
    private String createdAt;
}
