package com.local.erp.dto;

import lombok.Data;

import java.util.List;

/** 单据创建请求（草稿/直接过账通用） */
@Data
public class BillDto {
    private String type;
    private Long partnerId;
    private Long warehouseId;
    /** 调拨入仓 */
    private Long toWarehouseId;
    private Integer paid;
    /** 整单折扣%（默认100） */
    private Double discount;
    private String remark;
    /** true=保存即过账 */
    private Boolean autoPost;
    private List<Item> items;

    @Data
    public static class Item {
        private Long productId;
        private Integer quantity;
        private Double price;
        private String batchNo;
        private String productionDate;
    }
}
