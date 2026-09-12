package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 电商平台对接通道配置 */
@Data
@TableName("integration_channel")
public class IntegrationChannel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;     // TAOBAO/PDD/DOUYIN/MEITUAN
    private String shopName;
    private String configJson;   // appKey/appSecret/callback 等 JSON
    private String status;       // NOT_CONFIGURED/ENABLED/DISABLED
    private String lastSyncAt;
    private String createdAt;
}
