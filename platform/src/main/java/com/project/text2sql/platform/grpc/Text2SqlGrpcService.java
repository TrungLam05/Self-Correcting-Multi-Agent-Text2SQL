package com.project.text2sql.platform.grpc;

import com.project.text2sql.proto.v1.Text2SQLRequest;
import com.project.text2sql.proto.v1.Text2SQLResponse;
import com.project.text2sql.proto.v1.Text2SQLServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class Text2SqlGrpcService extends Text2SQLServiceGrpc.Text2SQLServiceImplBase {
   
    @Override 
    public void executeQuery(Text2SQLRequest request, StreamObserver<Text2SQLResponse> responseObserver) {
        Text2SQLResponse response = Text2SQLResponse.newBuilder().build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}