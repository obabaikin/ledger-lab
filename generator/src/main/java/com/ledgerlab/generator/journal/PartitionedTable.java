package com.ledgerlab.generator.journal;

public enum PartitionedTable {
    INTENTS("intents"),
    OUTCOMES("outcomes");

    private final String tableName;

    PartitionedTable(String tableName) {
        this.tableName = tableName;
    }

    public String tableName() {
        return tableName;
    }
}
