package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("bill_item")
public class BillItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long billId;
    private Long productId;
    /** 一律正数，方向由单据类型决定 */
    private Integer quantity;
    private Double price;
    /** 批次号 / 生产日期（采购登记，用于保质期临期查询） */
    private String batchNo;
    private String productionDate;
}
