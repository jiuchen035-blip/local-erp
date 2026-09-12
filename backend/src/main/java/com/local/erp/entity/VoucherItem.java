package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 凭证分录（借贷行） */
@Data
@TableName("voucher_item")
public class VoucherItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long voucherId;
    private Long accountId;
    private String direction;    // DEBIT借/CREDIT贷
    private Double amount;
    private String summary;
    private Long partnerId;
    private Double quantity;     // 数量金额式核算（可空）
    private Double unitPrice;    // 单价（可空）
    private Double taxRate;      // 适用税率%（计税时的价税分离分录，可空）
}
