package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.config.Text2SqlExecutorProperties;
import com.project.text2sql.platform.executor.dto.ExecuteSqlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SqlExecutorServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SqlSafetyPolicy safetyPolicy;

    @Mock
    private Text2SqlExecutorProperties props;

    private SqlExecutorService service;

    @BeforeEach
    void setUp() {
        service = new SqlExecutorService(jdbcTemplate, safetyPolicy, props);
        
        // Default properties (lenient to avoid unnecessary stubbing warnings in safety violation tests)
        lenient().when(props.getMaxLimit()).thenReturn(1000);
        lenient().when(props.getQueryTimeoutSeconds()).thenReturn(5);
    }

    // =====================================================================
    // SUCCESS CASES
    // =====================================================================

    @Test
    void execute_validSelectQuery_returnsSuccessResponse() {
        // Arrange
        String sql = "SELECT * FROM orders LIMIT 10";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenReturn(null);

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response);
        assertNull(response.error(), "Success response should have null error");
        assertEquals(sql, response.sanitizedSql());
        assertTrue(response.executionTimeMs() >= 0);
    }

    // =====================================================================
    // SAFETY VIOLATION ERRORS
    // =====================================================================

    @Test
    void execute_nonSelectStatement_returnsSafetyViolationError() {
        // Arrange
        String sql = "DELETE FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenThrow(new IllegalArgumentException("only SELECT statements are allowed"));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response);
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SAFETY_VIOLATION, response.error().errorCode());
        assertEquals("only SELECT statements are allowed", response.error().message());
        assertNull(response.error().sqlState());
        assertEquals(0, response.rowCount());
        assertTrue(response.columns().isEmpty());
        assertTrue(response.rows().isEmpty());
    }

    @Test
    void execute_multipleStatements_returnsSafetyViolationError() {
        // Arrange
        String sql = "SELECT 1; DROP TABLE users;";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenThrow(new IllegalArgumentException("only single-statement sql is allowed"));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SAFETY_VIOLATION, response.error().errorCode());
        assertTrue(response.error().message().contains("single-statement"));
    }

    @Test
    void execute_sqlTooLong_returnsSafetyViolationError() {
        // Arrange
        String sql = "SELECT * FROM orders WHERE ".repeat(1000);
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenThrow(new IllegalArgumentException("sql exceeds maximum length"));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SAFETY_VIOLATION, response.error().errorCode());
    }

    // =====================================================================
    // SYNTAX ERROR (SQLSTATE 42601)
    // =====================================================================

    @Test
    void execute_syntaxError_returnsSyntaxErrorCode() {
        // Arrange
        String sql = "SELECTT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: syntax error at or near \"SELECTT\"",
                "42601"
        );
        
        // FIXED: Added explicit type parameter
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Syntax error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SYNTAX_ERROR, response.error().errorCode());
        assertEquals("42601", response.error().sqlState());
        assertTrue(response.error().message().contains("syntax error"));
    }

    // =====================================================================
    // TABLE NOT FOUND (SQLSTATE 42P01)
    // =====================================================================

    @Test
    void execute_tableNotFound_returnsTableNotFoundError() {
        // Arrange
        String sql = "SELECT * FROM nonexistent_table";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: relation \"nonexistent_table\" does not exist",
                "42P01"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Table not found", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.TABLE_NOT_FOUND, response.error().errorCode());
        assertEquals("42P01", response.error().sqlState());
        assertTrue(response.error().message().contains("does not exist"));
    }

    // =====================================================================
    // COLUMN NOT FOUND (SQLSTATE 42703)
    // =====================================================================

    @Test
    void execute_columnNotFound_returnsColumnNotFoundError() {
        // Arrange
        String sql = "SELECT nonexistent_column FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: column \"nonexistent_column\" does not exist",
                "42703"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Column not found", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.COLUMN_NOT_FOUND, response.error().errorCode());
        assertEquals("42703", response.error().sqlState());
    }

    // =====================================================================
    // AMBIGUOUS COLUMN (SQLSTATE 42702)
    // =====================================================================

    @Test
    void execute_ambiguousColumn_returnsAmbiguousColumnError() {
        // Arrange
        String sql = "SELECT id FROM orders o JOIN customers c ON o.customer_id = c.id";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: column reference \"id\" is ambiguous",
                "42702"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Ambiguous column", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.AMBIGUOUS_COLUMN, response.error().errorCode());
        assertEquals("42702", response.error().sqlState());
    }

    // =====================================================================
    // PERMISSION DENIED (SQLSTATE 42501)
    // =====================================================================

    @Test
    void execute_permissionDenied_returnsPermissionDeniedError() {
        // Arrange
        String sql = "SELECT * FROM secure_table";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: permission denied for table secure_table",
                "42501"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Permission denied", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.PERMISSION_DENIED, response.error().errorCode());
        assertEquals("42501", response.error().sqlState());
    }

    // =====================================================================
    // TYPE ERROR (SQLSTATE 42804, 22P02)
    // =====================================================================

    @Test
    void execute_typeMismatch_returnsTypeError() {
        // Arrange
        String sql = "SELECT * FROM orders WHERE total_amount = 'not_a_number'";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: invalid input syntax for type numeric: \"not_a_number\"",
                "22P02"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Type error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.TYPE_ERROR, response.error().errorCode());
        assertEquals("22P02", response.error().sqlState());
    }

    // =====================================================================
    // ARITHMETIC ERROR (SQLSTATE 22012)
    // =====================================================================

    @Test
    void execute_divisionByZero_returnsArithmeticError() {
        // Arrange
        String sql = "SELECT 1/0";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: division by zero",
                "22012"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Division by zero", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.ARITHMETIC_ERROR, response.error().errorCode());
        assertEquals("22012", response.error().sqlState());
    }

    // =====================================================================
    // QUERY CANCELLED/TIMEOUT (SQLSTATE 57014)
    // =====================================================================

    @Test
    void execute_queryTimeout_returnsQueryCancelledError() {
        // Arrange
        String sql = "SELECT * FROM huge_table";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: canceling statement due to statement timeout",
                "57014"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Query timeout", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.QUERY_CANCELLED, response.error().errorCode());
        assertEquals("57014", response.error().sqlState());
    }

    // =====================================================================
    // DATABASE UNAVAILABLE (SQLSTATE 08xxx)
    // =====================================================================

    @Test
    void execute_connectionFailure_returnsDatabaseUnavailableError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: connection has been closed",
                "08003"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Connection closed", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.DATABASE_UNAVAILABLE, response.error().errorCode());
        assertEquals("08003", response.error().sqlState());
    }

    // =====================================================================
    // ERROR POSITION EXTRACTION
    // =====================================================================

    @Test
    void execute_syntaxErrorWithPosition_extractsPosition() {
        // Arrange
        String sql = "SELECTT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: syntax error at or near \"SELECTT\" at character 1",
                "42601"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Syntax error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertNotNull(response.error().position());
        assertEquals(1, response.error().position());
    }

    // =====================================================================
    // UNKNOWN ERROR HANDLING
    // =====================================================================

    @Test
    void execute_unknownSqlState_returnsExecutionError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: something weird happened",
                "XX999"  // Unknown SQLSTATE
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Unknown error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.EXECUTION_ERROR, response.error().errorCode());
        assertEquals("XX999", response.error().sqlState());
    }

    @Test
    void execute_nullSqlState_returnsExecutionError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        // Use explicit constructor: SQLException(reason, sqlState, vendorCode)
        SQLException sqlException = new SQLException("ERROR: unknown error", (String) null);
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Unknown error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.EXECUTION_ERROR, response.error().errorCode());
        assertNull(response.error().sqlState());
    }

    // =====================================================================
    // CLASS-LEVEL SQLSTATE MATCHING
    // =====================================================================

    @Test
    void execute_unknownClass42Error_mapToSyntaxError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql)).thenReturn(sql);
        
        SQLException sqlException = new SQLException(
                "ERROR: some class 42 error",
                "42999"  // Unknown but in syntax error class
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Class 42 error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SYNTAX_ERROR, response.error().errorCode());
    }

    // =====================================================================
    // Helper methods
    // =====================================================================

    /**
     * Helper method to create a typed ResultSetExtractor matcher.
     * This avoids unchecked conversion warnings.
     */
    @SuppressWarnings("unchecked")
    private <T> ResultSetExtractor<T> anyResultSetExtractor() {
        return any(ResultSetExtractor.class);
    }

    // =====================================================================
    // Helper class to wrap SQLException in DataAccessException
    // =====================================================================

    private static class TestDataAccessException extends DataAccessException {
        private final SQLException sqlException;

        public TestDataAccessException(String msg, SQLException cause) {
            super(msg, cause);
            this.sqlException = cause;
        }

        @Override
        public Throwable getCause() {
            return sqlException;
        }
    }
}