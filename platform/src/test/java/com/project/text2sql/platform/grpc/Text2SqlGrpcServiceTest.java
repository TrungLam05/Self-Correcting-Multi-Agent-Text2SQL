package com.project.text2sql.platform.grpc;

import com.project.text2sql.proto.v1.Text2SQLRequest;
import com.project.text2sql.proto.v1.Text2SQLResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class Text2SqlGrpcServiceTest {

    @Test
    void executeQuery_returnsEmptyResponse() {
        Text2SqlGrpcService service = new Text2SqlGrpcService();

        AtomicReference<Text2SQLResponse> got = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        service.executeQuery(Text2SQLRequest.newBuilder().build(), new StreamObserver<>() {
            @Override public void onNext(Text2SQLResponse value) {
                got.set(value);
            }

            @Override public void onError(Throwable t) {
                fail("Unexpected error: "  + t);
            }
            @Override public void onCompleted() {
                completed.set(true);
            }
        });
        assertNotNull(got.get());
        assertTrue(completed.get());
    }
}