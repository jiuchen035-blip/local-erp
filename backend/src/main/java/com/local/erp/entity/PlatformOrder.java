package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 平台订单落单表：Excel 导入或将来 API 拉单都落这里，映射到本地销售单 */
@Data
@TableName("platform_order")
public class PlatformOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private String platformOrderNo;
    private String buyer;
    private String orderTime;
    private String rawJson;
    private Long billId;         // 映射到的本地销售单
    private String status;       // IMPORTED/PENDING
    private String createdAt;
}
