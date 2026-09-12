package com.local.erp.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 旧库平滑升级：SQLite 不支持 IF NOT EXISTS 的加列语法，
 * 用 try/catch 幂等迁移；并为老库补默认仓库、归档历史单据。
 */
@Configuration
public class MigrationConfig {

    @Bean
    CommandLineRunner migrate(JdbcTemplate jdbc, com.local.erp.service.KbService kbService) {
        return args -> {
            try { jdbc.execute("ALTER TABLE stock_record ADD COLUMN partner_id INTEGER"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE stock_record ADD COLUMN warehouse_id INTEGER"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE stock_record ADD COLUMN paid INTEGER NOT NULL DEFAULT 1"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE stock_record ADD COLUMN cost_price REAL"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE stock_record ADD COLUMN bill_id INTEGER"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE stock_record ADD COLUMN bill_item_id INTEGER"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN avg_cost REAL"); } catch (Exception ignored) {}
            // 商品深度：条码/单位换算/保质期/多价格/两级分类
            try { jdbc.execute("ALTER TABLE product ADD COLUMN barcode TEXT"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN unit TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN big_unit TEXT"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN big_unit_rate INTEGER"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN shelf_life_days INTEGER"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN wholesale_price REAL"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN member_price REAL"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN sub_category TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN sub2_category TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE product ADD COLUMN no_alert INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {} // 1=不监控库存预警
            try { jdbc.execute("ALTER TABLE bill ADD COLUMN peer_bill_id INTEGER"); } catch (Exception ignored) {} // 同行调货：销售草稿关联的采购草稿
            // 价格体系：整单折扣；往来单位期初余额
            try { jdbc.execute("ALTER TABLE bill ADD COLUMN discount REAL NOT NULL DEFAULT 100"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE bill_item ADD COLUMN batch_no TEXT"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE bill_item ADD COLUMN production_date TEXT"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE partner ADD COLUMN opening_receivable REAL NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE partner ADD COLUMN opening_payable REAL NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            // 商品规格（如 500ml/24瓶箱装），v0.6.0
            try { jdbc.execute("ALTER TABLE product ADD COLUMN spec TEXT DEFAULT ''"); } catch (Exception ignored) {}
            // 年检续检留档：archived=1 表示已被续检取代（历史记录），列表默认不显示
            try { jdbc.execute("ALTER TABLE inspection ADD COLUMN archived INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            // 存量数据一次性整理：只归档"确实被续检取代过"的旧记录
            // （判据：存在一条 remark 为「续检自#<旧id>」的新记录）——同一客户名下各自独立的多条不受影响
            try {
                int archived = jdbc.update("UPDATE inspection SET archived = 1 WHERE archived = 0"
                        + " AND id IN (SELECT CAST(REPLACE(j.remark, '续检自#', '') AS INTEGER)"
                        + " FROM inspection j WHERE j.remark LIKE '续检自#%')");
                if (archived > 0) System.out.println("[数据迁移] 已归档被续检取代的历史年检记录 " + archived + " 条");
            } catch (Exception e) {
                System.err.println("[数据迁移] 年检历史归档失败：" + e.getMessage());
            }
            // 财务 v0.3.0：商品税率 / 凭证分录数量金额式+税率标记 / 科目数量核算
            try { jdbc.execute("ALTER TABLE product ADD COLUMN tax_rate REAL"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE voucher_item ADD COLUMN quantity REAL"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE voucher_item ADD COLUMN unit_price REAL"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE voucher_item ADD COLUMN tax_rate REAL"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE account ADD COLUMN quantity_enabled INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            // 加列完成后再建索引（旧库此时才具备 partner_id 列）
            try { jdbc.execute("CREATE INDEX IF NOT EXISTS idx_record_partner ON stock_record(partner_id)"); } catch (Exception ignored) {}
            // 移动加权成本初始化：以商品档案成本价作为期初均价；历史流水成本补0
            jdbc.update("UPDATE product SET avg_cost = cost_price WHERE avg_cost IS NULL");
            jdbc.update("UPDATE stock_record SET cost_price = 0 WHERE cost_price IS NULL");
            // 同行调货自动生成的壳档案（PS- SKU + 不预警）统一停用：不进商品库存，仅留作调货记账
            jdbc.update("UPDATE product SET enabled = 0 WHERE sku LIKE 'PS-%' AND no_alert = 1 AND enabled = 1");

            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM warehouse", Long.class);
            if (count == null || count == 0) {
                jdbc.update("INSERT INTO warehouse(name, remark) VALUES('主仓库','默认仓库')");
            }
            // 历史单据归入最早的仓库
            jdbc.update("UPDATE stock_record SET warehouse_id = (SELECT MIN(id) FROM warehouse) WHERE warehouse_id IS NULL");

            // 内置管理员 admin/admin123，首次登录后请修改
            Long userCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class);
            if (userCount != null && userCount == 0) {
                String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("admin123");
                jdbc.update("INSERT INTO sys_user(username, password_hash, role) VALUES('admin', ?, 'ADMIN')", hash);
            }

            // 知识库为空时内置一份操作指南，让 AI 助手开箱即可回答“怎么用”
            Long kbCount = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Long.class);
            if (kbCount != null && kbCount == 0) {
                kbService.addDocument("系统操作指南", GUIDE, "guide");
            }

            // 财务科目表为空时预置小企业常用科目（builtin=1 不可删，用户可自行新增）
            Long accCount = jdbc.queryForObject("SELECT COUNT(*) FROM account", Long.class);
            if (accCount != null && accCount == 0) {
                String[][] accounts = {
                        {"1001", "库存现金", "ASSET", "DEBIT"},
                        {"1002", "银行存款", "ASSET", "DEBIT"},
                        {"1122", "应收账款", "ASSET", "DEBIT"},
                        {"1123", "预付账款", "ASSET", "DEBIT"},
                        {"1405", "库存商品", "ASSET", "DEBIT"},
                        {"1601", "固定资产", "ASSET", "DEBIT"},
                        {"2202", "应付账款", "LIABILITY", "CREDIT"},
                        {"2241", "其他应付款", "LIABILITY", "CREDIT"},
                        {"4001", "实收资本", "EQUITY", "CREDIT"},
                        {"4103", "本年利润", "EQUITY", "CREDIT"},
                        {"6001", "主营业务收入", "PROFIT", "CREDIT"},
                        {"6051", "其他业务收入", "PROFIT", "CREDIT"},
                        {"6301", "营业外收入", "PROFIT", "CREDIT"},
                        {"6401", "主营业务成本", "PROFIT", "DEBIT"},
                        {"6601", "销售费用", "PROFIT", "DEBIT"},
                        {"6602", "管理费用", "PROFIT", "DEBIT"},
                        {"6711", "营业外支出", "PROFIT", "DEBIT"}
                };
                for (String[] a : accounts) {
                    jdbc.update("INSERT INTO account(code, name, category, direction, builtin) VALUES(?,?,?,?,1)",
                            a[0], a[1], a[2], a[3]);
                }
            }
            // 2221 应交税费-增值税：老库已预置过 17 科目也要补上（幂等）
            Long vat = jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE code = '2221'", Long.class);
            if (vat == null || vat == 0) {
                jdbc.update("INSERT INTO account(code, name, category, direction, builtin) VALUES('2221','应交税费-增值税','LIABILITY','CREDIT',1)");
            }
        };
    }

    private static final String GUIDE = """
            本地商家后台操作指南

            商品管理：在“商品管理”页面点击新增商品，填写商品名称、SKU（唯一编码）、分类、成本价、售价和安全库存。SKU 创建后不可修改。列表中库存低于安全库存的商品会标红。支持按名称或SKU搜索，支持导出Excel。

            进货与销售：在“进货/销售”页面开单。先选择单据类型（进货入库/销售出库/盘整），再选择仓库、商品，系统自动带出成本价（进货）或售价（销售），也可手动修改单价。销售数量超过库存会被拦截。选择往来单位并点“挂账”表示赊账，点“现结”表示当场结清。开单成功后可打印单据。

            库存预警：商品总库存低于或等于安全库存时出现在“库存预警”页面，可按建议量一键补货。建议补货量 = 安全库存×2 - 当前库存。

            供应商与客户：在“供应商/客户”页面维护往来单位档案，记录联系人、电话、地址。开单时选择的往来单位必须先在这里创建。

            应收应付：挂账的销售单形成应收款（客户欠我），挂账的采购单形成应付款（我欠供应商）。在“应收应付”页面按单位查看挂账金额，可展开明细、单笔核销或一键结清，支持打印对账单和导出Excel。

            仓库管理：支持多个仓库，库存按仓库独立核算。商品列表的“分仓库明细”列显示每个仓库的库存分布。

            冲正：开错的单据不要删除，使用“冲正”生成一条反向流水还原库存，原始单据保留以便审计。冲正后的单据不能再次冲正。

            财务记账（管理员）：单据过账会自动生成会计凭证（销售借现金/贷收入并结转成本，采购借库存商品/贷现金或应付，挂账自动改用应收应付科目，调拨不生成）。手工凭证保存时强制借贷平衡。科目余额表的本期借方合计应等于贷方合计；资产负债表右侧含“未结转损益”，做过“期末结转损益”后利润归入本年利润。所有报表支持导出Excel。

            电商对接（管理员）：淘宝/拼多多/抖音/美团平台可先配置密钥（保存在本机），API 自动拉单入口已预留；现阶段用“订单 Excel 导入”——下载模板、把平台订单按模板整理（同一单号多行商品会合并成一张单），导入后自动创建缺档商品（成本按售价计，请到商品管理修正以准确核算毛利）、自动开现结销售单并生成财务凭证，重复单号自动跳过。

            数据说明：所有数据保存在本机 backend/data/erp.db 文件中，删除该文件夹即可完全重置系统。
            """;
}
