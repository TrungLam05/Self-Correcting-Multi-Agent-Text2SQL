'''Shared contracts for Text2SQL agents.'''
from typing import List, Optional, Any, Literal
from pydantic import BaseModel, Field
from enum import Enum

class SchemaColumn(BaseModel):
    '''Represents a column in a database schema.'''
    name: str
    type: str
    description: Optional[str] = None
    nullable: bool = True
    primary_key: bool = False
    foreign_key: Optional[str] = None

class SchemaTable(BaseModel):
    '''Represents a table in a database schema.'''
    name: str
    description: Optional[str] = None
    columns: List[SchemaColumn]

class DatabaseSchema(BaseModel):
    '''Represents a database schema.'''
    tables: List[SchemaTable]

class FilterCondition(BaseModel):
    '''Represents a filter condition for SQL queries.'''
    column: str
    operator: Literal['=', '!=', '<', '<=', '>', '>=', 'LIKE', 'BETWEEN', 'IN', 'NOT IN']
    value: Any

class OrderByClause(BaseModel):
    '''Represents an ORDER BY clause for SQL queries.'''
    column: str
    direction: Literal['ASC', 'DESC'] = 'ASC'

class QueryIntent(BaseModel):
    '''Represents the intent of a SQL query.'''
    metric: str
    aggregation: Optional[Literal['SUM', 'AVG', 'COUNT', 'MIN', 'MAX']] = None
    filters: List[FilterCondition] = Field(default_factory=list)
    group_by: List[str] = Field(default_factory=list)
    order_by: Optional[OrderByClause] = None
    limit: Optional[int] = None
    tables: List[str] = Field(default_factory=list)

class RouterOutput(BaseModel):
    complexity: Literal['simple', 'moderate']
    strategy: Literal['single_candidate', 'multi_candidate']
    reasoning: str

class SQLOutput(BaseModel):
    sql_query: str
    confidence: float
    tables_used: List[str]

class ErrorType(str, Enum):
    """
    User Story A4: Defines the types of errors we can recognize.
    """
    SYNTAX = "syntax_error"          # Grammar mistakes (e.g., missing comma)
    SCHEMA = "schema_error"          # Wrong table/column names
    LOGIC = "logic_error"            # Math errors (e.g., divide by zero)
    UNKNOWN = "unknown_error"

class RepairInput(BaseModel):
    """
    User Story A5: What the Repair Agent needs to do its job.
    """
    bad_sql: str
    error_message: str
    error_type: ErrorType
    intent: Optional[QueryIntent] = None

class RepairOutput(BaseModel):
    """
    User Story A5: What the Repair Agent returns.
    """
    fixed_sql: str
    reasoning: str
    confidence: float

class MultiCandidateOutput(BaseModel):
    """
    User Story A6: A container for multiple SQL options.
    """
    candidates: List[SQLOutput]

class SqlVerificationIssue(BaseModel):
    kind: str
    message: str 

class SqlFacts(BaseModel):
    # Outer = the SELECT that produces the final rows
    outer_aggregations: List[str] = Field(default_factory=list)
    outer_selected_columns: List[str] = Field(default_factory=list)
    outer_group_by_columns: List[str] = Field(default_factory=list)
    outer_where_columns: List[str] = Field(default_factory=list)
    outer_having_columns: List[str] = Field(default_factory=list)
    outer_tables: List[str] = Field(default_factory=list)

    # Global = across the entire statement (CTEs/subqueries, etc) for debugging
    global_aggregations: List[str] = Field(default_factory=list)
    global_columns: List[str] = Field(default_factory=list)
    global_tables: List[str] = Field(default_factory=list)

    parse_dialect: str = "postgres"
    statement_type: Optional[str] = None 

class SqlVerificationResult(BaseModel):
    ok: bool
    issues: List[SqlVerificationIssue] = Field(default_factory=list)
    facts: SqlFacts = Field(default_factory=SqlFacts)