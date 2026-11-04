package com.jayant.spendsense.migration

/** Frozen copy of the v5 schema (SpendSense 1.2) — never edit: migrations must upgrade exactly this. */
object V5Schema {
    val CREATE = listOf(
        """
        CREATE TABLE txn (
            id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL, type TEXT NOT NULL, counterparty TEXT, account TEXT,
            bank TEXT, upi_ref TEXT, source TEXT NOT NULL, source_app TEXT, raw_text TEXT NOT NULL, timestamp INTEGER NOT NULL,
            category TEXT NOT NULL DEFAULT 'Other', is_split INTEGER NOT NULL DEFAULT 0, my_share REAL,
            reviewed INTEGER NOT NULL DEFAULT 0, note TEXT, payment_mode TEXT, refund_status TEXT NOT NULL DEFAULT 'NONE',
            refund_amount REAL, refund_of_id INTEGER, seen_sources TEXT, paid_for_others INTEGER NOT NULL DEFAULT 0, paid_for TEXT
        )
        """.trimIndent(),
        "CREATE INDEX idx_txn_ts ON txn(timestamp)",
        "CREATE INDEX idx_txn_ref ON txn(upi_ref)",
        """
        CREATE TABLE investment (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, asset_type TEXT NOT NULL, platform TEXT,
            invested_amount REAL NOT NULL, current_value REAL, units REAL, start_date INTEGER, maturity_date INTEGER,
            interest_rate REAL, is_sip INTEGER NOT NULL DEFAULT 0, sip_amount REAL, notes TEXT, updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS order_details (
            id INTEGER PRIMARY KEY AUTOINCREMENT, txn_id INTEGER UNIQUE, source TEXT NOT NULL, order_id TEXT NOT NULL,
            restaurant TEXT, items_json TEXT NOT NULL, total REAL NOT NULL, placed_at INTEGER NOT NULL,
            cancelled INTEGER NOT NULL DEFAULT 0, fetched_at INTEGER NOT NULL, UNIQUE(source, order_id)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_order_placed ON order_details(placed_at)",
    )

    /** A 1.2-era populated database: card spend, UPI debit with a split, refund pair, CC bill payment, cash, order. */
    val SEED = listOf(
        "INSERT INTO txn (id, amount, type, counterparty, account, bank, upi_ref, source, source_app, raw_text, timestamp, category, is_split, my_share, reviewed, note, payment_mode, seen_sources) VALUES " +
            "(1, 2340.0, 'DEBIT', 'Amazon', 'XX4321', 'Axis Bank', NULL, 'SMS', 'AX-AXISBK', 'INR 2,340.00 spent on Axis Bank Credit Card XX4321 at AMAZON on 24-10-26. Avl Lmt INR 1,22,000', 1761300000000, 'Shopping', 0, NULL, 1, NULL, 'CARD', 'SMS')",
        "INSERT INTO txn (id, amount, type, counterparty, account, bank, upi_ref, source, source_app, raw_text, timestamp, category, is_split, my_share, reviewed, note, payment_mode, seen_sources) VALUES " +
            "(2, 1200.0, 'DEBIT', 'Zomato', 'XX1234', 'HDFC Bank', '431234567890', 'SMS', 'VM-HDFCBK', 'Rs.1200.00 debited from A/c XX1234 to VPA zomato@hdfcbank UPI Ref 431234567890', 1761310000000, 'Food & Dining', 1, 300.0, 1, 'team lunch', 'UPI', 'SMS,NOTIFICATION')",
        "INSERT INTO txn (id, amount, type, counterparty, account, bank, upi_ref, source, source_app, raw_text, timestamp, category, reviewed, payment_mode, refund_status, refund_amount) VALUES " +
            "(3, 999.0, 'DEBIT', 'Myntra', 'XX5566', 'HDFC Bank', NULL, 'SMS', 'VM-HDFCBK', 'Rs 999 spent on HDFC Bank Card XX5566 at MYNTRA', 1761320000000, 'Shopping', 1, 'CARD', 'RECEIVED', 999.0)",
        "INSERT INTO txn (id, amount, type, counterparty, account, bank, upi_ref, source, source_app, raw_text, timestamp, category, reviewed, payment_mode, refund_of_id) VALUES " +
            "(4, 999.0, 'CREDIT', 'Myntra', 'XX5566', 'HDFC Bank', NULL, 'SMS', 'VM-HDFCBK', 'Refund of Rs 999 from MYNTRA credited to HDFC Bank Card XX5566', 1761400000000, 'Refund', 1, 'CARD', 3)",
        "INSERT INTO txn (id, amount, type, counterparty, account, bank, upi_ref, source, source_app, raw_text, timestamp, category, reviewed, payment_mode) VALUES " +
            "(5, 12450.0, 'DEBIT', 'HDFC Credit Card', 'XX1234', 'HDFC Bank', '512345678901', 'NOTIFICATION', 'com.dreamplug.androidapp', '₹12,450 paid to HDFC Bank credit card bill XX5566 successfully', 1761500000000, 'Transfers', 1, 'UPI')",
        "INSERT INTO txn (id, amount, type, counterparty, account, bank, upi_ref, source, source_app, raw_text, timestamp, category, reviewed, payment_mode, paid_for_others, paid_for, is_split, my_share) VALUES " +
            "(6, 300.0, 'DEBIT', 'Chai Stall', NULL, NULL, NULL, 'MANUAL', 'manual', '', 1761600000000, 'Food & Dining', 1, 'CASH', 1, 'Rahul', 1, 0.0)",
        "INSERT INTO order_details (txn_id, source, order_id, restaurant, items_json, total, placed_at, fetched_at) VALUES " +
            "(2, 'zomato', 'Z-1', 'Biryani House', '[{\"name\":\"Biryani\",\"qty\":2,\"price\":600}]', 1200.0, 1761309000000, 1761310500000)",
        "INSERT INTO investment (name, asset_type, invested_amount, updated_at) VALUES ('Index fund', 'MUTUAL_FUND', 50000.0, 1761000000000)",
    )
}
