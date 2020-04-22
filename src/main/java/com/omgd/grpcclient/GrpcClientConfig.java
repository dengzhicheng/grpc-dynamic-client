package com.omgd.grpcclient;

import com.google.common.base.Strings;

/**
 * <p>
 * grpc client config
 * </p>
 *
 * @author dengzhicheng
 * @since 2020-04-23
 */
public class GrpcClientConfig {
    private String name;
    private String protoFileContent;
    private String address;
    private int connections;

    private GrpcClientConfig() {
    }

    public void validate() {
        if (Strings.isNullOrEmpty(name) || Strings.isNullOrEmpty(protoFileContent) || Strings.isNullOrEmpty(address)
                || connections < 0) {
            throw new IllegalArgumentException("GrpcClientConfig is Illegal");
        }
    }

    public static Builder custom() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String protoFileContent;
        private String address;
        private int connections;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder protoFileContent(String protoFileContent) {
            this.protoFileContent = protoFileContent;
            return this;
        }

        public Builder address(String address) {
            this.address = address;
            return this;
        }

        public Builder connections(int connections) {
            this.connections = connections;
            return this;
        }

        public GrpcClientConfig build() {
            GrpcClientConfig config = new GrpcClientConfig();
            config.name = name;
            config.protoFileContent = protoFileContent;
            config.address = address;
            config.connections = connections;
            return config;
        }
    }

    public String getName() {
        return name;
    }

    public String getProtoFileContent() {
        return protoFileContent;
    }

    public String getAddress() {
        return address;
    }

    public int getConnections() {
        return connections;
    }

}
