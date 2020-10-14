package com.omgd.grpcclient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.os72.protobuf.dynamic.DynamicSchema;
import com.github.os72.protobuf.dynamic.EnumDefinition;
import com.github.os72.protobuf.dynamic.MessageDefinition;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.squareup.protoparser.EnumElement;
import com.squareup.protoparser.FieldElement;
import com.squareup.protoparser.MessageElement;
import com.squareup.protoparser.ProtoFile;
import com.squareup.protoparser.ProtoParser;
import com.squareup.protoparser.RpcElement;
import com.squareup.protoparser.ServiceElement;
import com.squareup.protoparser.TypeElement;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;

/**
 * <p>
 * grpc client component
 * </p>
 *
 * @author dengzhicheng
 * @since 2020-04-23
 */
public class DynamicGrpcClients {

    /**
     * schema map
     */
    private static volatile ConcurrentHashMap<String, DynamicSchema> SCHEMA_MAP = new ConcurrentHashMap<>(500);
    /**
     * server map
     */
    private static volatile ConcurrentHashMap<String, Map<String, String[]>> SERVER_MAP = new ConcurrentHashMap<>(500);
    /**
     * connection pool map
     */
    private static volatile ConcurrentHashMap<String, ManagedChannel[]> CHANNEL_MAP = new ConcurrentHashMap<>(500);
    /**
     * connection pool cursor map
     */
    private static volatile ConcurrentHashMap<String, AtomicInteger> CHANNEL_CURSOR_MAP = new ConcurrentHashMap<>(500);

    private String name;
    private String api;
    private String paramsJson;
    private int timeout;

    private DynamicGrpcClients() {
    }

    public static void registerClient(GrpcClientConfig clientCfg) {
        clientCfg.validate();
        if (SCHEMA_MAP.containsKey(clientCfg.getName())) {
            throw new IllegalArgumentException(String.format("client[ %s ] already registered, please unRegisterClient first", clientCfg.getName()));
        }
        initSchema(clientCfg);
        initChannel(clientCfg);
    }

    public static void unRegisterClient(String name) {
        SCHEMA_MAP.remove(name);
        SERVER_MAP.remove(name);
        CHANNEL_CURSOR_MAP.remove(name);
        ManagedChannel[] channels = CHANNEL_MAP.remove(name);
        if (channels != null) {
            for (ManagedChannel channel : channels) {
                channel.shutdownNow();
            }
        }
    }

    public GrpcResponse execute() {
        ManagedChannel channel = this.roundAccessChannel();
        DynamicSchema schema = SCHEMA_MAP.get(this.name);
        String[] typeNames = SERVER_MAP.get(this.name).get(this.api);
        GrpcCode code = null;
        String msg = null;
        String response = null;
        try {
            response = innerExecute(schema, channel, this.api, this.paramsJson, this.timeout, typeNames[0],  typeNames[1]);
            code = GrpcCode.OK;
        } catch (InvalidProtocolBufferException | InterruptedException | ExecutionException e) {
            code = GrpcCode.ERROR;
            msg = e.getMessage();
        } catch (TimeoutException e) {
            // trigger reconnection when timeout occurs
            channel.enterIdle();
            code = GrpcCode.TIMEOUT;
        }
        
        return new GrpcResponse(code, msg, response);
    }

    private void validate() {
        if (Strings.isNullOrEmpty(name) || Strings.isNullOrEmpty(api) || Strings.isNullOrEmpty(paramsJson) || timeout < 0) {
            throw new IllegalArgumentException("DynamicGrpcClients is Illegal");
        }
        if (!SCHEMA_MAP.containsKey(name)) {
            throw new IllegalArgumentException(String.format("client[ %s ] not registered yet", name));
        }
        if (!SERVER_MAP.containsKey(name) || !SERVER_MAP.get(name).containsKey(api)) {
            throw new IllegalArgumentException(String.format("server[ %s ] not registered yet", api));
        }
    }

    private ManagedChannel roundAccessChannel() {
        ManagedChannel[] channels = CHANNEL_MAP.get(this.name);
        AtomicInteger channelCursor = CHANNEL_CURSOR_MAP.get(this.name);
        if (channels == null) {
            throw new IllegalArgumentException(String.format("client[ %s ] not registered yet", name));
        }
        if (channels.length == 1) {
            return channels[0];
        }
        int num = channelCursor.getAndIncrement();
        if (num > Integer.MAX_VALUE >> 1) {
            channelCursor.set(0);
        }
        int cursor = num % channels.length;
        ManagedChannel channel = channels[cursor];
        return channel;
    }

    public static Builder custom() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String api;
        private String paramsJson;
        private int timeout;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder api(String api) {
            this.api = api;
            return this;
        }

        public Builder paramsJson(String paramsJson) {
            this.paramsJson = paramsJson;
            return this;
        }

        public Builder timeout(int timeout) {
            this.timeout = timeout;
            return this;
        }

        public DynamicGrpcClients build() {
            DynamicGrpcClients client = new DynamicGrpcClients();
            client.name = name;
            client.api = api;
            client.paramsJson = paramsJson;
            client.timeout = timeout;
            client.validate();
            return client;
        }
    }

    private static void initSchema(GrpcClientConfig clientCfg) {
        String name = clientCfg.getName();
        String protoFileContent = clientCfg.getProtoFileContent();
        ProtoFile protoFile = null;
        try {
            protoFile = ProtoParser.parse("", protoFileContent);
        } catch (Exception e) {
            throw new RuntimeException(String.format("initSchema parse protoFileContent error %s", e.getMessage()), e);
        }
        List<TypeElement> typeList = protoFile.typeElements();
        List<ServiceElement> serviceList = protoFile.services();
        if (typeList.size() == 0 || serviceList.size() == 0) {
            throw new RuntimeException("initSchema typeElementList is empty or serviceElementList is empty");
        }
        DynamicSchema.Builder schemaBuilder = DynamicSchema.newBuilder();
        typeList.forEach(typeElement -> {
            if (typeElement instanceof EnumElement) {
                EnumElement element = (EnumElement) typeElement;
                EnumDefinition.Builder definitionBuilder = EnumDefinition.newBuilder(element.name());
                element.constants().forEach(field -> definitionBuilder.addValue(field.name(), field.tag()));
                EnumDefinition definition = definitionBuilder.build();
                schemaBuilder.addEnumDefinition(definition);
            } else if (typeElement instanceof MessageElement) {
                MessageElement element = (MessageElement) typeElement;
                List<FieldElement> fields = element.fields();
                MessageDefinition.Builder definitionBuilder = MessageDefinition.newBuilder(element.name());
                fields.forEach(field -> definitionBuilder.addField(field.label().name().toLowerCase(), field.type().toString(), field.name(), field.tag()));
                MessageDefinition definition = definitionBuilder.build();
                schemaBuilder.addMessageDefinition(definition);
            }
        });

        Map<String,String[]> serviceMap = new HashMap<>(serviceList.size());
        SERVER_MAP.put(name, serviceMap);
        serviceList.forEach(serviceElement -> {
            List<RpcElement> rpcList = serviceElement.rpcs();
            rpcList.forEach(rpcElement -> {
                String api = String.format("%s/%s", serviceElement.name(), rpcElement.name());
                String[] types = new String[] { rpcElement.requestType().toString(), rpcElement.responseType().toString() };
                serviceMap.put(api, types);
            });
        });
        DynamicSchema schema = null;
        try {
            schema = schemaBuilder.build();
        } catch (DescriptorValidationException e) {
            throw new RuntimeException(String.format("initSchema schema build error %s", e.getMessage()), e);
        }
        SCHEMA_MAP.put(name, schema);
    }

    private static void initChannel(GrpcClientConfig clientCfg) {
        String name = clientCfg.getName();
        String address = clientCfg.getAddress();
        int connections = clientCfg.getConnections();
        ManagedChannel[] channels = new ManagedChannel[connections];
        for (int i = 0; i < connections; i++) {
            channels[i] = NettyChannelBuilder.forTarget(address).defaultLoadBalancingPolicy("round_robin")
                    .keepAliveTimeout(3, TimeUnit.SECONDS).keepAliveWithoutCalls(true).usePlaintext().build();
        }

        // establish connection
        try {
            DynamicSchema schema = SCHEMA_MAP.get(name);
            String api = null;
            String requestTypeName = null;
            String replyTypeName = null;
            for (Entry<String, String[]> entry : SERVER_MAP.get(name).entrySet()) {
                api = entry.getKey();
                requestTypeName = entry.getValue()[0];
                replyTypeName = entry.getValue()[1];
                break;
            }
            for (ManagedChannel channel : channels) {
                try {
                    innerExecute(schema, channel, api, "{}", 10000, requestTypeName, replyTypeName);
                } catch (Exception e) {
                    // e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        AtomicInteger channelCursor = new AtomicInteger(0);
        CHANNEL_MAP.put(name, channels);
        CHANNEL_CURSOR_MAP.put(name, channelCursor);
    }

    private static String innerExecute(DynamicSchema schema, ManagedChannel channel, String api, String paramJson,
            int timeout, String requestTypeName, String replyTypeName)
            throws InvalidProtocolBufferException, InterruptedException, ExecutionException, TimeoutException {

        // 1）serialize request
        DynamicMessage.Builder requestBuilder = schema.newMessageBuilder(requestTypeName);
        com.google.protobuf.util.JsonFormat.parser().ignoringUnknownFields().merge(paramJson, requestBuilder);
        DynamicMessage msg = requestBuilder.build();
        byte[] request = msg.toByteArray();

        // 2）grpc call
        MethodDescriptor<byte[], byte[]> methodDescriptor = MethodDescriptor.<byte[], byte[]> newBuilder()
                .setRequestMarshaller(new ByteArrayMarshaller()).setResponseMarshaller(new ByteArrayMarshaller())
                .setType(MethodType.UNARY).setFullMethodName(api).build();
        ListenableFuture<byte[]> future = ClientCalls
                .futureUnaryCall(channel.newCall(methodDescriptor, CallOptions.DEFAULT.withDeadlineAfter(timeout, TimeUnit.MILLISECONDS)), request);
        byte[] reply = future.get(timeout, TimeUnit.MILLISECONDS);

        // 3）unserialize response
        DynamicMessage.Builder replyMsgBuilder = schema.newMessageBuilder(replyTypeName);
        DynamicMessage replyMsg = replyMsgBuilder.mergeFrom(reply).build();
        String response = com.google.protobuf.util.JsonFormat.printer().includingDefaultValueFields().print(replyMsg);
        return response;
    }

    private static class ByteArrayMarshaller implements Marshaller<byte[]> {
        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return ByteStreams.toByteArray(stream);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
