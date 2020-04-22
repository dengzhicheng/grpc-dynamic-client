package com.omgd.grpcclient;

/**
 * <p>
 * grpc response
 * </p>
 *
 * @author dengzhicheng
 * @since 2020-04-23
 */
public class GrpcResponse {

    private GrpcCode code;
    private String msg;
    private String bodyJson;

    public GrpcResponse(GrpcCode code, String msg, String bodyJson) {
        super();
        this.code = code;
        this.msg = msg;
        this.bodyJson = bodyJson;
    }

    public GrpcCode getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public String getBodyJson() {
        return bodyJson;
    }

    @Override
    public String toString() {
        return new StringBuilder().append("code=").append(code).append("|msg=").append(msg).append("|bodyJson=").append(bodyJson).toString();
    }

}
