package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 同行库存：从同行处可调到的货源目录（与自己商品档案区分，开单勾选同行行时从这里选） */
@Data
@TableName("peer_stock")
public class PeerStock {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 同行（供应商档案 id） */
    private Long partnerId;
    /** 商品名称（同行货品，可不进自己商品档案） */
    private String productName;
    /** 自动生成：PS-000001 递增 */
    private String sku;
    private String category;
    /** 最近一次调货价 */
    private Double lastPrice;
    private String unit;
    /** 关联自己商品档案（首次用于开单时自动建档后回填，未调过货为空） */
    private Long productId;
    private String createdAt;
}
