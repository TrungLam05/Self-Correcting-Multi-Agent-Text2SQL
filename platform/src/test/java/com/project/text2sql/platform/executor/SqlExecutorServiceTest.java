package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.config.Text2SqlExecutorProperties;
import com.project.text2sql.platform.executor.dto.ExecuteSqlResponse;
import com.project.text2sql.platform.executor.dto.SqlRepairMetadata;
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
        
        lenient().when(props.getMaxLimit()).thenReturn(1000);
        lenient().when(props.getQueryTimeoutSeconds()).thenReturn(5);
    }

    @Test
    void execute_validSelectQuery_returnsSuccessResponse() {
        // Arrange
        String sql = "SELECT * FROM orders LIMIT 10";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenReturn(null);

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response);
        assertNull(response.error(), "Success response should have null error");
        assertEquals(sql, response.sanitizedSql());
        assertTrue(response.executionTimeMs() >= 0);
        assertNotNull(response.repairMetadata());
        assertFalse(response.repairMetadata().wasRepaired());
        assertEquals(0, response.retryCount());
    }

    // =====================================================================
    // SAFETY VIOLATIONS (IllegalArgumentException from SafetyPolicy)
    // =====================================================================

    @Test
    void execute_nonSelectStatement_returnsSafetyViolationError() {
        // Arrange
        String sql = "DELETE FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenThrow(new SqlSanitizationException("only SELECT statements are allowed", 
                    SqlSanitizationException.ViolationType.FORBIDDEN_STATEMENT_TYPE));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response);
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SAFETY_VIOLATION, response.error().errorCode());
        assertTrue(response.error().message().contains("SELECT"));
        assertEquals(0, response.retryCount());
    }

    @Test
    void execute_multipleStatements_returnsSafetyViolationError() {
        // Arrange
        String sql = "SELECT 1; DROP TABLE users;";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenThrow(new SqlSanitizationException("only single-statement sql is allowed",
                    SqlSanitizationException.ViolationType.MULTIPLE_STATEMENTS));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response);
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SAFETY_VIOLATION, response.error().errorCode());
        assertTrue(response.error().message().contains("single-statement"));
    }

    @Test
    void execute_sqlTooLong_returnsSafetyViolationError() {
        // Arrange
        String sql = "SELECT * FROM orders WHERE ".repeat(1000);
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenThrow(new SqlSanitizationException("sql exceeds maximum length",
                    SqlSanitizationException.ViolationType.EXCEEDS_LENGTH_LIMIT));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response);
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SAFETY_VIOLATION, response.error().errorCode());
    }

    // =====================================================================
    // SYNTAX ERROR (SQLSTATE 42601)
    // =====================================================================

    @Test
    void execute_syntaxError_returnsSyntaxErrorCode() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
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
        assertEquals(SqlErrorCode.SYNTAX_ERROR, response.error().errorCode());
        assertEquals("42601", response.error().sqlState());
        assertNotNull(response.error().position());
        assertEquals(1, response.error().position());
    }

    // =====================================================================
    // TABLE NOT FOUND (SQLSTATE 42P01)
    // =====================================================================

    @Test
    void execute_tableNotFound_returnsTableNotFoundError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
        SQLException sqlException = new SQLException(
            "ERROR: relation \"nonexistent\" does not exist", 
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
    }

    // =====================================================================
    // COLUMN NOT FOUND (SQLSTATE 42703)
    // =====================================================================

    @Test
    void execute_columnNotFound_returnsColumnNotFoundError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
        SQLException sqlException = new SQLException(
            "ERROR: column \"bad_column\" does not exist at character 8", 
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
        assertEquals(8, response.error().position());
    }

    // =====================================================================
    // AMBIGUOUS COLUMN (SQLSTATE 42702)
    // =====================================================================

    @Test
    void execute_ambiguousColumn_returnsAmbiguousColumnError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
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
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
        SQLException sqlException = new SQLException(
            "ERROR: permission denied for table orders", 
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
    // TYPE ERROR (SQLSTATE 22P02, 42804)
    // =====================================================================

    @Test
    void execute_typeError_returnsTypeError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
        SQLException sqlException = new SQLException(
            "ERROR: invalid input syntax for type integer: \"abc\"", 
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
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
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
    // QUERY CANCELLED (SQLSTATE 57014)
    // =====================================================================

    @Test
    void execute_queryCancelled_returnsQueryCancelledError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
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
    void execute_connectionFailure_returnsDatabaseUnavailable() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
        SQLException sqlException = new SQLException(
            "ERROR: connection refused", 
            "08006"
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Connection error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.DATABASE_UNAVAILABLE, response.error().errorCode());
        assertEquals("08006", response.error().sqlState());
    }

    // =====================================================================
    // UNKNOWN ERRORS
    // =====================================================================

    @Test
    void execute_unknownSqlState_returnsExecutionError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
        // Use explicit constructor: SQLException(reason, sqlState, vendorCode)
        SQLException sqlException = new SQLException("ERROR: unknown error", (String) null);
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Unknown error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.EXECUTION_ERROR, response.error().errorCode());
    }

    // =====================================================================
    // CLASS-LEVEL SQLSTATE FALLBACK
    // =====================================================================

    @Test
    void execute_unknownSyntaxClass_fallsBackToSyntaxError() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
        SQLException sqlException = new SQLException(
            "ERROR: some syntax issue", 
            "42999"  // Unknown 42xxx code
        );
        
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Syntax class error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SYNTAX_ERROR, response.error().errorCode());
    }

    // =====================================================================
    // REPAIR METADATA TESTS (B6)
    // =====================================================================

    @Test
    void execute_sqlWithoutLimit_includesRepairMetadata() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.repaired(
                    sql,
                    "SELECT * FROM orders LIMIT 200",
                    "Added default LIMIT"
                ));
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenReturn(null);

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response);
        assertNull(response.error());
        assertNotNull(response.repairMetadata());
        assertTrue(response.repairMetadata().wasRepaired());
        assertEquals("Added default LIMIT", response.repairMetadata().repairReason());
        assertEquals(sql, response.repairMetadata().originalSql());
        assertEquals("SELECT * FROM orders LIMIT 200", response.repairMetadata().repairedSql());
        assertEquals(0, response.retryCount());
    }

    @Test
    void execute_sqlWithValidLimit_noRepairNeeded() {
        // Arrange
        String sql = "SELECT * FROM orders LIMIT 50";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenReturn(null);

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response);
        assertNull(response.error());
        assertNotNull(response.repairMetadata());
        assertFalse(response.repairMetadata().wasRepaired());
        assertNull(response.repairMetadata().repairReason());
        assertEquals(sql, response.repairMetadata().originalSql());
        assertEquals(sql, response.repairMetadata().repairedSql());
    }

    @Test
    void execute_sqlError_includesRepairMetadataAndRetryCount() {
        // Arrange
        String sql = "SELECT * FROM orders";
        when(safetyPolicy.sanitizeAndEnforceLimit(sql))
                .thenReturn(SqlRepairMetadata.noRepair(sql));
        
        SQLException sqlException = new SQLException("Table not found", "42P01");
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), this.<Object>anyResultSetExtractor()))
                .thenThrow(new TestDataAccessException("Error", sqlException));

        // Act
        ExecuteSqlResponse response = service.execute(sql);

        // Assert
        assertNotNull(response);
        assertNotNull(response.error());
        assertEquals(0, response.retryCount());
        assertNotNull(response.repairMetadata());
        assertFalse(response.repairMetadata().wasRepaired());
    }

    // =====================================================================
    // HELPER METHODS
    // =====================================================================

    @SuppressWarnings("unchecked")
    private <T> ResultSetExtractor<T> anyResultSetExtractor() {
        return any(ResultSetExtractor.class);
    }

    // Test exception to simulate Spring's DataAccessException
    private static class TestDataAccessException extends DataAccessException {
        public TestDataAccessException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}