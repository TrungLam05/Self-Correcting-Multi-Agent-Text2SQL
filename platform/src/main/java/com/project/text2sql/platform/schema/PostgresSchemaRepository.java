package com.project.text2sql.platform.schema;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.project.text2sql.platform.schema.dto.ColumnSchemaDto;
import com.project.text2sql.platform.schema.dto.ForeignKeySchemaDto;
import com.project.text2sql.platform.schema.dto.IndexSchemaDto;
import com.project.text2sql.platform.schema.dto.TableSchemaDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSchemaRepository {
    private final JdbcTemplate jdbcTemplate;

    public PostgresSchemaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TableSchemaDto> loadSchema(String schema) {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """,
                String.class,
                schema
        );

        Map<String, String> tableComments = loadTableComments(schema);
        Map<String, List<ColumnSchemaDto>> columns = loadColumns(schema);
        Map<String, List<String>> primaryKeys = loadPrimaryKeys(schema);
        Map<String, List<ForeignKeySchemaDto>> foreignKeys = loadForeignKeys(schema);
        Map<String, List<IndexSchemaDto>> indexes = loadIndexes(schema);

        List<TableSchemaDto> out = new ArrayList<>();
        for (String table : tables) {
            String key = key(schema, table);
            out.add(new TableSchemaDto(
                    schema,
                    table,
                    tableComments.getOrDefault(key, null),
                    columns.getOrDefault(key, List.of()),
                    primaryKeys.getOrDefault(key, List.of()),
                    foreignKeys.getOrDefault(key, List.of()),
                    indexes.getOrDefault(key, List.of())
            ));
        }
        return out;
    }

    public TableSchemaDto loadTableSchema(String schema, String table) {
        String key = key(schema, table);
        String comment = loadTableComments(schema).getOrDefault(key, null);
        Map<String, List<ColumnSchemaDto>> columns = loadColumns(schema);
        Map<String, List<String>> primaryKeys = loadPrimaryKeys(schema);
        Map<String, List<ForeignKeySchemaDto>> foreignKeys = loadForeignKeys(schema);
        Map<String, List<IndexSchemaDto>> indexes = loadIndexes(schema);

        return new TableSchemaDto(
                schema,
                table,
                comment,
                columns.getOrDefault(key, List.of()),
                primaryKeys.getOrDefault(key, List.of()),
                foreignKeys.getOrDefault(key, List.of()),
                indexes.getOrDefault(key, List.of())
        );
    }

    private Map<String, String> loadTableComments(String schema) {
        String sql = """
            SELECT
                c.relname AS table_name,
                obj_description(c.oid, 'pg_class') AS table_comment
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relkind = 'r'
            ORDER BY c.relname
            """;

        Map<String, String> out = new HashMap<>();
        jdbcTemplate.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(key(schema, rs.getString("table_name")), rs.getString("table_comment")), schema);
        return out;
    }

    private Map<String, List<ColumnSchemaDto>> loadColumns(String schema) {
        String sql = """
            SELECT
                table_name,
                column_name,
                udt_name,
                is_nullable,
                column_default,
                col_description((quote_ident(table_schema) || '.' || quote_ident(table_name))::regclass::oid, ordinal_position) AS column_comment,
                ordinal_position
            FROM information_schema.columns
            WHERE table_schema = ?
            ORDER BY table_name, ordinal_position
            """;

        Map<String, List<ColumnSchemaDto>> out = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String tableName = rs.getString("table_name");
            out.computeIfAbsent(key(schema, tableName), k -> new ArrayList<>()).add(new ColumnSchemaDto(
                    rs.getString("column_name"),
                    rs.getString("udt_name"),
                    "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                    rs.getString("column_default"),
                    rs.getString("column_comment")
            ));
        }, schema);

        return out;
    }

    private Map<String, List<String>> loadPrimaryKeys(String schema) {
        String sql = """
            SELECT
                kcu.table_name,
                kcu.column_name,
                kcu.ordinal_position
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY'
              AND kcu.table_schema = ?
            ORDER BY kcu.table_name, kcu.ordinal_position
            """;

        Map<String, List<String>> out = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String tableName = rs.getString("table_name");
            out.computeIfAbsent(key(schema, tableName), k -> new ArrayList<>()).add(rs.getString("column_name"));
        }, schema);

        return out;
    }

    private Map<String, List<ForeignKeySchemaDto>> loadForeignKeys(String schema) {
        // composite-key-safe mapping using pg_catalog with ordinality
        String sql = """
            SELECT
                rel.relname  AS fk_table,
                con.conname  AS fk_name,
                att2.attname AS fk_column,
                nsp2.nspname AS pk_schema,
                rel2.relname AS pk_table,
                att.attname  AS pk_column,
                fk_cols.ord  AS position
            FROM pg_constraint con
            JOIN pg_class rel ON rel.oid = con.conrelid
            JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
            JOIN pg_class rel2 ON rel2.oid = con.confrelid
            JOIN pg_namespace nsp2 ON nsp2.oid = rel2.relnamespace
            JOIN LATERAL unnest(con.conkey)  WITH ORDINALITY AS fk_cols(attnum, ord) ON true
            JOIN LATERAL unnest(con.confkey) WITH ORDINALITY AS pk_cols(attnum, ord) ON pk_cols.ord = fk_cols.ord
            JOIN pg_attribute att2 ON att2.attrelid = con.conrelid  AND att2.attnum = fk_cols.attnum
            JOIN pg_attribute att  ON att.attrelid  = con.confrelid AND att.attnum  = pk_cols.attnum
            WHERE con.contype = 'f'
              AND nsp.nspname = ?
            ORDER BY fk_table, fk_name, position
            """;

        record FkKey(String table, String name) {}
        Map<String, Map<FkKey, MutableForeignKey>> working = new HashMap<>();

        jdbcTemplate.query(sql, rs -> {
            String fkTable = rs.getString("fk_table");
            String fkName = rs.getString("fk_name");
            String fkColumn = rs.getString("fk_column");
            String pkSchema = rs.getString("pk_schema");
            String pkTable = rs.getString("pk_table");
            String pkColumn = rs.getString("pk_column");

            String tableKey = key(schema, fkTable);
            working.computeIfAbsent(tableKey, k -> new HashMap<>());
            FkKey fkKey = new FkKey(fkTable, fkName);

            MutableForeignKey fk = working.get(tableKey).computeIfAbsent(
                    fkKey,
                    k -> new MutableForeignKey(fkName, pkSchema, pkTable)
            );

            fk.columns.add(fkColumn);
            fk.referencesColumns.add(pkColumn);
        }, schema);

        Map<String, List<ForeignKeySchemaDto>> out = new HashMap<>();
        for (Map.Entry<String, Map<FkKey, MutableForeignKey>> e : working.entrySet()) {
            List<ForeignKeySchemaDto> list = new ArrayList<>();
            for (MutableForeignKey fk : e.getValue().values()) {
                list.add(new ForeignKeySchemaDto(
                        fk.name,
                        List.copyOf(fk.columns),
                        fk.referencesSchema,
                        fk.referencesTable,
                        List.copyOf(fk.referencesColumns)
                ));
            }
            out.put(e.getKey(), list);
        }

        return out;
    }

    private Map<String, List<IndexSchemaDto>> loadIndexes(String schema) {
        String sql = """
            SELECT
                t.relname AS table_name,
                i.relname AS index_name,
                ix.indisunique AS is_unique,
                array_agg(a.attname ORDER BY arr.idx) AS columns
            FROM pg_class t
            JOIN pg_namespace ns ON ns.oid = t.relnamespace
            JOIN pg_index ix ON ix.indrelid = t.oid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS arr(attnum, idx) ON true
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = arr.attnum
            WHERE ns.nspname = ?
              AND t.relkind = 'r'
            GROUP BY t.relname, i.relname, ix.indisunique
            ORDER BY t.relname, i.relname
            """;

        RowMapper<IndexRow> mapper = (rs, rowNum) -> new IndexRow(
                rs.getString("table_name"),
                rs.getString("index_name"),
                rs.getBoolean("is_unique"),
                toStringArray(rs.getArray("columns"))
        );

        List<IndexRow> rows = jdbcTemplate.query(sql, mapper, schema);
        Map<String, List<IndexSchemaDto>> out = new HashMap<>();
        for (IndexRow r : rows) {
            out.computeIfAbsent(key(schema, r.tableName), k -> new ArrayList<>()).add(
                    new IndexSchemaDto(r.indexName, r.unique, List.of(r.columns))
            );
        }

        return out;
    }

    private static String[] toStringArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return new String[0];
        }
        Object raw = sqlArray.getArray();
        if (raw instanceof String[] sa) {
            return sa;
        }
        if (raw instanceof Object[] oa) {
            String[] out = new String[oa.length];
            for (int i = 0; i < oa.length; i++) {
                out[i] = oa[i] == null ? null : String.valueOf(oa[i]);
            }
            return out;
        }
        return new String[0];
    }

    private static String key(String schema, String table) {
        return schema + "." + table;
    }

    private record IndexRow(String tableName, String indexName, boolean unique, String[] columns) {
    }

    private static final class MutableForeignKey {
        private final String name;
        private final String referencesSchema;
        private final String referencesTable;
        private final List<String> columns = new ArrayList<>();
        private final List<String> referencesColumns = new ArrayList<>();

        private MutableForeignKey(String name, String referencesSchema, String referencesTable) {
            this.name = name;
            this.referencesSchema = referencesSchema;
            this.referencesTable = referencesTable;
        }
    }
}