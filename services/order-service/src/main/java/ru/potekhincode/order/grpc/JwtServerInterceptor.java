package ru.potekhincode.order.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

@GrpcGlobalServerInterceptor
@RequiredArgsConstructor
public class JwtServerInterceptor  implements ServerInterceptor {

    public static final Context.Key<String> USER_ID = Context.key("userId");
    public static final Context.Key<String> ROLE = Context.key("role");

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(ServerCall<R, S> call,
                                                       Metadata metadata,
                                                       ServerCallHandler<R, S> next) {
        String header = metadata.get(AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return deny(call, "Missing Bearer token");
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()));
        } catch (JwtException e) {
            return deny(call, "Invalid token");
        }

        Context context = Context.current()
                .withValue(USER_ID, jwt.getSubject())
                .withValue(ROLE, jwt.getClaimAsString("role"));
        return Contexts.interceptCall(context, call, metadata, next);
    }

    private <R, S> ServerCall.Listener<R> deny(ServerCall<R, S> call, String description) {
        call.close(Status.UNAUTHENTICATED.withDescription(description), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
