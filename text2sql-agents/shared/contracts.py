'''Shared contracts for Text2SQL agents.'''
from typing import List, Optional, Any, Literal
from pydantic import BaseModel, Field

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