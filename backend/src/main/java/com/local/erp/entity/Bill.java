package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("bill")
public class Bill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String billNo;
    /** PURCHASE/SALE/PURCHASE_RETURN/SALE_RETURN/LOSS/GAIN/TRANSFER */
    private String type;
    private Long partnerId;
    private Long warehouseId;
    private Long toWarehouseId;
    /** DRAFT / POSTED */
    private String status;
    private Integer paid;
    /** 整单折扣%（100=无折扣），过账时按行分摊 */
    private Double discount;
    private Double totalAmount;
    private String remark;
    private String createdBy;
    private String createdAt;
    private String postedAt;
    /** 同行调货：销售草稿关联的同行采购草稿 id（确认过账时连带过账） */
    private Long peerBillId;
}
