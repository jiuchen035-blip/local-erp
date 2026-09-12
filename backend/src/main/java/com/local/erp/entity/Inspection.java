package com.local.erp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("inspection")
public class Inspection {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String customerName;
    private String phone;
    private String address;
    /** 用途：工地/商场/仓库/学校等 */
    private String purpose;
    /** 灭火器规格，如 4kg干粉 */
    private String spec;
    private Long productId;
    private Integer quantity;
    private Double price;
    private Double totalAmount;
    /** 本次年检日期 YYYY-MM-DD */
    private String inspectDate;
    /** 下次年检日期，默认本次+12个月，可人工修改 */
    private String nextDate;
    private String remark;
    /** 1=已被续检取代，属历史记录，列表默认不显示（保留留档，不物理删除） */
    private Integer archived;
    private String createdBy;
    private String createdAt;
}
