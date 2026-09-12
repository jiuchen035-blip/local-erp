package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("op_log")
public class OpLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String action;
    private String detail;
    private String createdAt;
}
