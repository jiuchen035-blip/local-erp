package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 会计科目 */
@Data
@TableName("account")
public class Account {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String category;    // ASSET/LIABILITY/EQUITY/COST/PROFIT
    private String direction;   // DEBIT/CREDIT 余额方向
    private Integer enabled;
    private Integer builtin;    // 1=预置科目不可删
    private Integer quantityEnabled; // 1=数量金额式核算（如库存商品）
    private String createdAt;
}
