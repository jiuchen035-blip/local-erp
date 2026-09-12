package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("partner")
public class Partner {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** SUPPLIER / CUSTOMER */
    private String type;
    private String name;
    private String contact;
    private String phone;
    private String address;
    private String remark;
    /** 期初余额（开业建账） */
    private Double openingReceivable;
    private Double openingPayable;
    private String createdAt;
}
