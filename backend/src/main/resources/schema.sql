-- 本地进销存核心表（SQLite，可重复执行）
CREATE TABLE IF NOT EXISTS product (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    sku         TEXT NOT NULL UNIQUE,
    category    TEXT DEFAULT '',
    spec        TEXT DEFAULT '',               -- 规格（如 500ml/4kg干粉）
    sale_price  REAL NOT NULL DEFAULT 0,      -- 售价
    cost_price  REAL NOT NULL DEFAULT 0,      -- 成本价
    safe_stock  INTEGER NOT NULL DEFAULT 0,   -- 安全库存（低于则预警）
    tax_rate    REAL,                          -- 增值税率%（如13/9/6/0，空=跟随全局设置）
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 供应商/客户档案
CREATE TABLE IF NOT EXISTS partner (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    type        TEXT NOT NULL CHECK (type IN ('SUPPLIER','CUSTOMER')),  -- 供应商/客户
    name        TEXT NOT NULL,
    contact     TEXT DEFAULT '',
    phone       TEXT DEFAULT '',
    address     TEXT DEFAULT '',
    remark      TEXT DEFAULT '',
    created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 仓库
CREATE TABLE IF NOT EXISTS warehouse (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    remark      TEXT DEFAULT '',
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 库存流水：采购/销售都记一条流水，库存=流水聚合，保证可追溯、可冲正
CREATE TABLE IF NOT EXISTS stock_record (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id    INTEGER NOT NULL REFERENCES product(id),
    type          TEXT NOT NULL CHECK (type IN ('PURCHASE','SALE','ADJUST')), -- 采购入库/销售出库/盘整
    quantity      INTEGER NOT NULL,            -- 正数；方向由 type 决定
    price         REAL NOT NULL DEFAULT 0,
    partner_id    INTEGER REFERENCES partner(id),   -- 往来单位（供应商/客户）
    warehouse_id  INTEGER REFERENCES warehouse(id), -- 出入仓库
    paid          INTEGER NOT NULL DEFAULT 1,       -- 1现结 0挂账（应收/应付）
    remark        TEXT DEFAULT '',
    created_at    TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_record_product ON stock_record(product_id);
CREATE INDEX IF NOT EXISTS idx_record_time    ON stock_record(created_at);

-- 往来账（预留）
CREATE TABLE IF NOT EXISTS ledger (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    party_name  TEXT NOT NULL,
    direction   TEXT NOT NULL CHECK (direction IN ('RECEIVABLE','PAYABLE')),
    amount      REAL NOT NULL,
    settled     INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 单据主表（一张单据多行商品，管家婆式）
CREATE TABLE IF NOT EXISTS bill (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    bill_no         TEXT NOT NULL UNIQUE,
    type            TEXT NOT NULL CHECK (type IN ('PURCHASE','SALE','PURCHASE_RETURN','SALE_RETURN','LOSS','GAIN','TRANSFER')),
    partner_id      INTEGER REFERENCES partner(id),
    warehouse_id    INTEGER NOT NULL,                 -- 出/入仓库（调拨=出仓）
    to_warehouse_id INTEGER,                          -- 调拨入仓
    status          TEXT NOT NULL DEFAULT 'DRAFT',    -- DRAFT草稿 / POSTED已过账
    paid            INTEGER NOT NULL DEFAULT 1,
    total_amount    REAL NOT NULL DEFAULT 0,
    remark          TEXT DEFAULT '',
    created_by      TEXT DEFAULT '',
    created_at      TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    posted_at       TEXT
);
CREATE TABLE IF NOT EXISTS bill_item (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    bill_id     INTEGER NOT NULL REFERENCES bill(id) ON DELETE CASCADE,
    product_id  INTEGER NOT NULL REFERENCES product(id),
    quantity    INTEGER NOT NULL,                     -- 一律正数，方向由单据类型决定
    price       REAL NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_bill_time ON bill(created_at);

-- 操作员
CREATE TABLE IF NOT EXISTS sys_user (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'OPERATOR',   -- ADMIN / OPERATOR
    enabled       INTEGER NOT NULL DEFAULT 1,
    created_at    TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 操作日志
CREATE TABLE IF NOT EXISTS op_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    username   TEXT NOT NULL,
    action     TEXT NOT NULL,
    detail     TEXT DEFAULT '',
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 灭火器年检档案：12个月周期，到期可人工调整；状态按 next_date 动态计算
CREATE TABLE IF NOT EXISTS inspection (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_name TEXT NOT NULL,                 -- 客户名称
    phone        TEXT DEFAULT '',                -- 联系电话
    address      TEXT DEFAULT '',
    purpose      TEXT DEFAULT '',                -- 用途：工地/商场/仓库/学校等
    spec         TEXT DEFAULT '',                -- 灭火器规格（如 4kg干粉）
    product_id   INTEGER REFERENCES product(id), -- 可选关联商品档案
    quantity     INTEGER NOT NULL DEFAULT 1,     -- 数量
    price        REAL NOT NULL DEFAULT 0,        -- 年检单价
    total_amount REAL NOT NULL DEFAULT 0,        -- 总价快照
    inspect_date TEXT NOT NULL,                  -- 本次年检日期
    next_date    TEXT NOT NULL,                  -- 下次年检日期（默认+12个月，可改）
    remark       TEXT DEFAULT '',
    created_by   TEXT DEFAULT '',
    archived     INTEGER NOT NULL DEFAULT 0,     -- 1=已被续检取代（历史记录），列表默认不显示
    created_at   TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_inspection_next ON inspection(next_date);

-- 知识库：文档 + 切片（embedding 存 JSON 浮点数组，小规模暴力余弦检索）
CREATE TABLE IF NOT EXISTS kb_document (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT NOT NULL,
    source      TEXT DEFAULT 'manual',
    created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS kb_chunk (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    doc_id      INTEGER NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    content     TEXT NOT NULL,
    embedding   TEXT,
    created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- ============ 财务模块：会计科目 / 记账凭证 / 凭证分录 ============
CREATE TABLE IF NOT EXISTS account (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    code       TEXT NOT NULL UNIQUE,               -- 科目编码（如 1001）
    name       TEXT NOT NULL,                      -- 科目名称
    category   TEXT NOT NULL,                      -- ASSET资产/LIABILITY负债/EQUITY权益/COST成本/PROFIT损益
    direction  TEXT NOT NULL DEFAULT 'DEBIT',      -- 余额方向 DEBIT借/CREDIT贷
    enabled    INTEGER NOT NULL DEFAULT 1,
    builtin    INTEGER NOT NULL DEFAULT 0,         -- 预置科目不可删除
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS voucher (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    voucher_no   TEXT NOT NULL UNIQUE,             -- 记-0001 递增
    voucher_date TEXT NOT NULL,                    -- 凭证日期 yyyy-MM-dd
    source_type  TEXT NOT NULL DEFAULT 'MANUAL',   -- BILL单据自动/MANUAL手工/OPENING期初/CLOSE结转
    source_id    INTEGER,                          -- 关联单据id（source_type=BILL 时）
    summary      TEXT DEFAULT '',
    total_amount REAL NOT NULL DEFAULT 0,
    created_by   TEXT DEFAULT '',
    created_at   TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_voucher_date ON voucher(voucher_date);

CREATE TABLE IF NOT EXISTS voucher_item (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    voucher_id INTEGER NOT NULL REFERENCES voucher(id) ON DELETE CASCADE,
    account_id INTEGER NOT NULL REFERENCES account(id),
    direction  TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),  -- 借/贷
    amount     REAL NOT NULL DEFAULT 0,
    summary    TEXT DEFAULT '',
    partner_id INTEGER REFERENCES partner(id)
);
CREATE INDEX IF NOT EXISTS idx_vitem_voucher ON voucher_item(voucher_id);
CREATE INDEX IF NOT EXISTS idx_vitem_account ON voucher_item(account_id);

-- ============ 电商对接：通道配置 + 平台订单落单表 ============
-- 同行库存：从同行处可调到的货源目录（与自己商品档案区分）
CREATE TABLE IF NOT EXISTS peer_stock (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    partner_id   INTEGER,                           -- 同行（供应商档案）
    product_name TEXT NOT NULL,                     -- 商品名称
    sku          TEXT NOT NULL UNIQUE,              -- 自动生成 PS-000001
    category     TEXT DEFAULT '',
    last_price   REAL NOT NULL DEFAULT 0,           -- 最近调货价
    unit         TEXT DEFAULT '个',
    product_id   INTEGER,                           -- 关联自己商品档案（开单调货时自动建档回填）
    created_at   TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS integration_channel (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    platform     TEXT NOT NULL UNIQUE,             -- TAOBAO/PDD/DOUYIN/MEITUAN
    shop_name    TEXT DEFAULT '',
    config_json  TEXT DEFAULT '{}',                -- appKey/appSecret/callback 等（JSON）
    status       TEXT NOT NULL DEFAULT 'NOT_CONFIGURED', -- NOT_CONFIGURED/ENABLED/DISABLED
    last_sync_at TEXT,
    created_at   TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 平台订单落单表：Excel 导入或将来 API 拉单都落这里，映射到本地销售单
CREATE TABLE IF NOT EXISTS platform_order (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    platform          TEXT NOT NULL,
    platform_order_no TEXT NOT NULL,
    buyer             TEXT DEFAULT '',
    order_time        TEXT DEFAULT '',
    raw_json          TEXT DEFAULT '',
    bill_id           INTEGER REFERENCES bill(id),   -- 映射到的本地销售单
    status            TEXT NOT NULL DEFAULT 'IMPORTED',
    created_at        TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    UNIQUE(platform, platform_order_no)
);
