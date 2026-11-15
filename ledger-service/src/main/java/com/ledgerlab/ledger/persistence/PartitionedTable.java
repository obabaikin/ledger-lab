package com.ledgerlab.ledger.persistence;

public enum PartitionedTable {
    TRANSFERS("transfers"),
    JOURNAL_ENTRIES("journal_entries");

    private final String tableName;

    PartitionedTable(String tableName) {
        this.tableName = tableName;
    }

    public String tableName() {
        return tableName;
    }
}
