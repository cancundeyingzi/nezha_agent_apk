package com.nezhahq.agent.grpc

import io.grpc.*

class AuthInterceptor(private val secret: String, private val uuid: String) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel
    ): ClientCall<ReqT, RespT> {
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions)
        ) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                AuthMetadataWriter.write(headers, secret, uuid)
                super.start(responseListener, headers)
            }
        }
    }
}
