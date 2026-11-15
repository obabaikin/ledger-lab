package com.ledgerlab.ledger.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PoolRepository {

    private static final String POOL_LOCK = "ledger.pool";

    private final JdbcClient jdbc;

    public PoolRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Held until the transaction ends: of all instances, one moves the pool at a time. */
    public boolean tryLock() {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", POOL_LOCK)
            .query(Boolean.class)
            .single();
    }

    public PoolState state() {
        return jdbc.sql("SELECT next_account_no, round_advanced_at, hot_swept_at FROM pool_state")
            .query((rs, rowNum) -> new PoolState(
                rs.getInt("next_account_no"), Sql.instant(rs, "round_advanced_at"), Sql.instant(rs, "hot_swept_at")))
            .single();
    }

    public void save(PoolState state) {
        jdbc.sql("UPDATE pool_state SET next_account_no = :next, round_advanced_at = :advancedAt, hot_swept_at = :sweptAt")
            .param("next", state.nextAccountNo())
            .param("advancedAt", Sql.timestamptz(state.roundAdvancedAt()))
            .param("sweptAt", Sql.timestamptz(state.hotSweptAt()))
            .update();
    }
}
