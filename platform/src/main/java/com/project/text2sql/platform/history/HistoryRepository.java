package com.project.text2sql.platform.history;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class HistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public HistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<QueryHistory> rowMapper = (rs, rowNum) -> new QueryHistory(
            rs.getInt("id"),
            rs.getString("natural_language_query"),
            rs.getString("generated_sql"),
            rs.getString("status"),
            rs.getInt("execution_time_ms"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    public void save(String nlQuery, String sql, Integer timeMs) {
        String insertSql = """
            INSERT INTO query_history (natural_language_query, generated_sql, execution_time_ms, status) 
            VALUES (?, ?, ?, 'verified')
        """;
        jdbcTemplate.update(insertSql, nlQuery, sql, timeMs);
    }

    public List<QueryHistory> findRecent() {
        return jdbcTemplate.query(
                "SELECT * FROM query_history ORDER BY created_at DESC LIMIT 50",
                rowMapper
        );
    }
}