package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("stock_record")
public class StockRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    /** PURCHASE / SALE / ADJUST */
    private String type;
    private Integer quantity;
    private Double price;
    /** 往来单位（供应商/客户） */
    private Long partnerId;
    /** 出入仓库 */
    private Long warehouseId;
    /** 1现结 0挂账 */
    private Integer paid;
    /** 结转成本（销售毛利用） */
    private Double costPrice;
    private Long billId;
    private Long billItemId;
    private String remark;
    private String createdAt;
}
