package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ledger")
public class Ledger {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String partyName;
    /** RECEIVABLE / PAYABLE */
    private String direction;
    private Double amount;
    private Integer settled;
    private String createdAt;
}
