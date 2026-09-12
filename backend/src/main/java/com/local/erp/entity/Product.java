package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("product")
public class Product {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String sku;
    private String category;
    /** 规格（如 500ml/4kg干粉） */
    private String spec;
    private Double salePrice;
    private Double costPrice;
    private Integer safeStock;
    private Double taxRate;          // 增值税率%（空=跟随全局计税设置）
    /** 移动加权平均成本 */
    private Double avgCost;
    /** 条码（扫码枪） */
    private String barcode;
    /** 基本单位（瓶/个） */
    private String unit;
    /** 大件单位（箱）与换算率（1箱=?基本单位） */
    private String bigUnit;
    private Integer bigUnitRate;
    /** 保质期天数（按生产日期+天数算到期） */
    private Integer shelfLifeDays;
    /** 批发价 / 会员价（sale_price为零售价） */
    private Double wholesalePrice;
    private Double memberPrice;
    /** 二级分类 */
    private String subCategory;
    /** 1=不监控库存预警（同行调货等卖完即止的商品） */
    private Integer noAlert;
    /** 三级分类 */
    private String sub2Category;
    private Integer enabled;
    private String createdAt;
}
