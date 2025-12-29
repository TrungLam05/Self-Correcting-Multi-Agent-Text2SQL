package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.config.Text2SqlExecutorProperties;
import com.project.text2sql.platform.executor.dto.ExecuteSqlResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

@Service
public class SqlExecutorService {
    private final JdbcTemplate jdbcTemplate;
    private final SqlSafetyPolicy safetyPolicy;
    private final Text2SqlExecutorProperties props;

    public SqlExecutorService(JdbcTemplate jdbcTemplate, SqlSafetyPolicy safetyPolicy, Text2SqlExecutorProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.safetyPolicy = safetyPolicy;
        this.props = props;
    }

    public ExecuteSqlResponse execute(String rawSql) {
        String sanitizedSql = safetyPolicy.sanitizeAndEnforceLimit(rawSql);

        int maxRows = Math.max(1, props.getMaxLimit());
        int timeoutSec = Math.max(1, props.getQueryTimeoutSeconds());

        long start = System.nanoTime();

        List<String> columns = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        boolean[] truncated = new boolean[] { false };

        jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(sanitizedSql);
            ps.setQueryTimeout(timeoutSec);
            ps.setMaxRows(maxRows + 1);
            ps.setFetchSize(Math.min(200, maxRows));
            return ps;
        }, rs -> {
            var md = rs.getMetaData();
            int colCount = md.getColumnCount();
            columns.clear();
            for (int i = 1; i <= colCount; i++){
                columns.add(md.getColumnLabel(i));
            }

            int count = 0;
            while (rs.next()) {
                count++;
                if (count > maxRows) {
                    truncated[0] = true;
                    break;
                }
                List<String> row = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    Object v = rs.getObject(i);
                    row.add(v == null ? null : String.valueOf(v));
                }
                rows.add(row);
            }

            return null;
        });
        
        long ms = (System.nanoTime() - start) / 1_000_000L;
        return new ExecuteSqlResponse(sanitizedSql, columns, rows, truncated[0], rows.size(), ms);
    }
}