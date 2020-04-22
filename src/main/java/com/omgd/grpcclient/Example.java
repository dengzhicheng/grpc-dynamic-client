package com.omgd.grpcclient;

import java.io.File;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;

public class Example {

    public static void main(String[] args) throws Exception {

        String name = "xxx-grpc";
        File file = new File(Example.class.getClass().getResource("/example.proto").getPath());
        String protoFileContent = Resources.toString(file.toURI().toURL(), Charsets.UTF_8);
        // # address examples
        // # 10.0.0.10:9090
        // # dns:/example.com
        String address = "127.0.0.1:8888";
        int connections = 5;
        // registerClient
        GrpcClientConfig clientCfg = GrpcClientConfig.custom().name(name).protoFileContent(protoFileContent).address(address).connections(connections).build();
        DynamicGrpcClients.registerClient(clientCfg);

        // grpc call
        String api = "ExampleServer/info";
        String paramsJson = "{\"name\":\"abc\",\"title\":\"abcd\"}";
        int timeout = 5000;
        DynamicGrpcClients client = DynamicGrpcClients.custom().name(name).api(api).paramsJson(paramsJson).timeout(timeout).build();
        GrpcResponse response = client.execute();
        System.out.println(response);
    }
    
}
