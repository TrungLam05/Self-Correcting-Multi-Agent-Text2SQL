package com.project.text2sql.platform.grpc;

import java.time.Instant;

import com.project.text2sql.platform.schema.SchemaService;
import com.project.text2sql.platform.schema.dto.ColumnSchemaDto;
import com.project.text2sql.platform.schema.dto.ForeignKeySchemaDto;
import com.project.text2sql.platform.schema.dto.IndexSchemaDto;
import com.project.text2sql.platform.schema.dto.SchemaSnapshotDto;
import com.project.text2sql.platform.schema.dto.TableSchemaDto;
import com.project.text2sql.proto.v1.ColumnSchema;
import com.project.text2sql.proto.v1.ForeignKeySchema;
import com.project.text2sql.proto.v1.GetSchemaRequest;
import com.project.text2sql.proto.v1.GetSchemaResponse;
import com.project.text2sql.proto.v1.GetTableSchemaRequest;
import com.project.text2sql.proto.v1.GetTableSchemaResponse;
import com.project.text2sql.proto.v1.IndexSchema;
import com.project.text2sql.proto.v1.SchemaServiceGrpc;
import com.project.text2sql.proto.v1.TableSchema;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;


@GrpcService
public class SchemaGrpcService extends SchemaServiceGrpc.SchemaServiceImplBase {
    private final SchemaService schemaService;

    public SchemaGrpcService(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @Override
    public void getSchema(GetSchemaRequest request, StreamObserver<GetSchemaResponse> responseObserver) {
        SchemaSnapshotDto snapshot = schemaService.getSchemaSnapshot(request.getSchema());
        GetSchemaResponse.Builder resp = GetSchemaResponse.newBuilder()
                .setDatabase(nullToEmpty(snapshot.database()))
                .setSchema(nullToEmpty(snapshot.schema()))
                .setGeneratedAtEpochMs(snapshot.generatedAt().toEpochMilli())
                .setTtlSeconds(snapshot.ttlSeconds());

        for (TableSchemaDto t : snapshot.tables()) {
            resp.addTables(toProto(t));
        }

        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getTableSchema(GetTableSchemaRequest request, StreamObserver<GetTableSchemaResponse> responseObserver) {
        Instant now = Instant.now();
        TableSchemaDto table = schemaService.getTableSchema(request.getSchema(), request.getTable());
        SchemaSnapshotDto snapshot = schemaService.getSchemaSnapshot(request.getSchema());

        GetTableSchemaResponse.Builder resp = GetTableSchemaResponse.newBuilder()
                .setGeneratedAtEpochMs(now.toEpochMilli())
                .setTtlSeconds(snapshot.ttlSeconds());

        if (table != null) {
            resp.setTable(toProto(table));
        }

        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    private static TableSchema toProto(TableSchemaDto dto) {
        TableSchema.Builder b = TableSchema.newBuilder()
                .setSchema(nullToEmpty(dto.schema()))
                .setName(nullToEmpty(dto.name()))
                .setComment(nullToEmpty(dto.comment()));

        for (ColumnSchemaDto c : dto.columns()) {
            b.addColumns(ColumnSchema.newBuilder()
                    .setName(nullToEmpty(c.name()))
                    .setDataType(nullToEmpty(c.dataType()))
                    .setNullable(c.nullable())
                    .setDefaultValue(nullToEmpty(c.defaultValue()))
                    .setComment(nullToEmpty(c.comment()))
                    .build());
        }

        b.addAllPrimaryKeyColumns(dto.primaryKeyColumns());

        for (ForeignKeySchemaDto fk : dto.foreignKeys()) {
            b.addForeignKeys(ForeignKeySchema.newBuilder()
                    .setName(nullToEmpty(fk.name()))
                    .addAllColumns(fk.columns())
                    .setReferencesSchema(nullToEmpty(fk.referencesSchema()))
                    .setReferencesTable(nullToEmpty(fk.referencesTable()))
                    .addAllReferencesColumns(fk.referencesColumns())
                    .build());
        }

        for (IndexSchemaDto ix : dto.indexes()) {
            b.addIndexes(IndexSchema.newBuilder()
                    .setName(nullToEmpty(ix.name()))
                    .setUnique(ix.unique())
                    .addAllColumns(ix.columns())
                    .build());
        }

        return b.build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}