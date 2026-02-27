package cds.distdownloader.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * peer can send message to tracker and receive acknowledgement; start tracking peer
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: tracker.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class PeerGrpc {

  private PeerGrpc() {}

  public static final java.lang.String SERVICE_NAME = "cds.distdownloader.v1.Peer";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<cds.distdownloader.proto.PeerEndpoint,
      cds.distdownloader.proto.Ack> getRegisterMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Register",
      requestType = cds.distdownloader.proto.PeerEndpoint.class,
      responseType = cds.distdownloader.proto.Ack.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<cds.distdownloader.proto.PeerEndpoint,
      cds.distdownloader.proto.Ack> getRegisterMethod() {
    io.grpc.MethodDescriptor<cds.distdownloader.proto.PeerEndpoint, cds.distdownloader.proto.Ack> getRegisterMethod;
    if ((getRegisterMethod = PeerGrpc.getRegisterMethod) == null) {
      synchronized (PeerGrpc.class) {
        if ((getRegisterMethod = PeerGrpc.getRegisterMethod) == null) {
          PeerGrpc.getRegisterMethod = getRegisterMethod =
              io.grpc.MethodDescriptor.<cds.distdownloader.proto.PeerEndpoint, cds.distdownloader.proto.Ack>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Register"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  cds.distdownloader.proto.PeerEndpoint.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  cds.distdownloader.proto.Ack.getDefaultInstance()))
              .setSchemaDescriptor(new PeerMethodDescriptorSupplier("Register"))
              .build();
        }
      }
    }
    return getRegisterMethod;
  }

  private static volatile io.grpc.MethodDescriptor<cds.distdownloader.proto.Peer.Heartbeat,
      cds.distdownloader.proto.Ack> getBeatMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Beat",
      requestType = cds.distdownloader.proto.Peer.Heartbeat.class,
      responseType = cds.distdownloader.proto.Ack.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<cds.distdownloader.proto.Peer.Heartbeat,
      cds.distdownloader.proto.Ack> getBeatMethod() {
    io.grpc.MethodDescriptor<cds.distdownloader.proto.Peer.Heartbeat, cds.distdownloader.proto.Ack> getBeatMethod;
    if ((getBeatMethod = PeerGrpc.getBeatMethod) == null) {
      synchronized (PeerGrpc.class) {
        if ((getBeatMethod = PeerGrpc.getBeatMethod) == null) {
          PeerGrpc.getBeatMethod = getBeatMethod =
              io.grpc.MethodDescriptor.<cds.distdownloader.proto.Peer.Heartbeat, cds.distdownloader.proto.Ack>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Beat"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  cds.distdownloader.proto.Peer.Heartbeat.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  cds.distdownloader.proto.Ack.getDefaultInstance()))
              .setSchemaDescriptor(new PeerMethodDescriptorSupplier("Beat"))
              .build();
        }
      }
    }
    return getBeatMethod;
  }

  private static volatile io.grpc.MethodDescriptor<cds.distdownloader.proto.Peer.ReportChunk,
      cds.distdownloader.proto.Ack> getReportMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Report",
      requestType = cds.distdownloader.proto.Peer.ReportChunk.class,
      responseType = cds.distdownloader.proto.Ack.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<cds.distdownloader.proto.Peer.ReportChunk,
      cds.distdownloader.proto.Ack> getReportMethod() {
    io.grpc.MethodDescriptor<cds.distdownloader.proto.Peer.ReportChunk, cds.distdownloader.proto.Ack> getReportMethod;
    if ((getReportMethod = PeerGrpc.getReportMethod) == null) {
      synchronized (PeerGrpc.class) {
        if ((getReportMethod = PeerGrpc.getReportMethod) == null) {
          PeerGrpc.getReportMethod = getReportMethod =
              io.grpc.MethodDescriptor.<cds.distdownloader.proto.Peer.ReportChunk, cds.distdownloader.proto.Ack>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Report"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  cds.distdownloader.proto.Peer.ReportChunk.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  cds.distdownloader.proto.Ack.getDefaultInstance()))
              .setSchemaDescriptor(new PeerMethodDescriptorSupplier("Report"))
              .build();
        }
      }
    }
    return getReportMethod;
  }

  private static volatile io.grpc.MethodDescriptor<cds.distdownloader.proto.PeerEndpoint,
      cds.distdownloader.v1.Tracker.AllPeers> getSendMessageMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendMessage",
      requestType = cds.distdownloader.proto.PeerEndpoint.class,
      responseType = cds.distdownloader.v1.Tracker.AllPeers.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<cds.distdownloader.proto.PeerEndpoint,
      cds.distdownloader.v1.Tracker.AllPeers> getSendMessageMethod() {
    io.grpc.MethodDescriptor<cds.distdownloader.proto.PeerEndpoint, cds.distdownloader.v1.Tracker.AllPeers> getSendMessageMethod;
    if ((getSendMessageMethod = PeerGrpc.getSendMessageMethod) == null) {
      synchronized (PeerGrpc.class) {
        if ((getSendMessageMethod = PeerGrpc.getSendMessageMethod) == null) {
          PeerGrpc.getSendMessageMethod = getSendMessageMethod =
              io.grpc.MethodDescriptor.<cds.distdownloader.proto.PeerEndpoint, cds.distdownloader.v1.Tracker.AllPeers>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendMessage"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  cds.distdownloader.proto.PeerEndpoint.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  cds.distdownloader.v1.Tracker.AllPeers.getDefaultInstance()))
              .setSchemaDescriptor(new PeerMethodDescriptorSupplier("SendMessage"))
              .build();
        }
      }
    }
    return getSendMessageMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static PeerStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PeerStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PeerStub>() {
        @java.lang.Override
        public PeerStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PeerStub(channel, callOptions);
        }
      };
    return PeerStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static PeerBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PeerBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PeerBlockingStub>() {
        @java.lang.Override
        public PeerBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PeerBlockingStub(channel, callOptions);
        }
      };
    return PeerBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static PeerFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PeerFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PeerFutureStub>() {
        @java.lang.Override
        public PeerFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PeerFutureStub(channel, callOptions);
        }
      };
    return PeerFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * peer can send message to tracker and receive acknowledgement; start tracking peer
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Called once when a peer comes online so tracker can register it.
     * </pre>
     */
    default void register(cds.distdownloader.proto.PeerEndpoint request,
        io.grpc.stub.StreamObserver<cds.distdownloader.proto.Ack> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterMethod(), responseObserver);
    }

    /**
     * <pre>
     * Called periodically so that tracker keeps this peer marked alive.
     * </pre>
     */
    default void beat(cds.distdownloader.proto.Peer.Heartbeat request,
        io.grpc.stub.StreamObserver<cds.distdownloader.proto.Ack> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getBeatMethod(), responseObserver);
    }

    /**
     * <pre>
     * Called after a peer gets a chunk so tracker updates chunk ownership.
     * </pre>
     */
    default void report(cds.distdownloader.proto.Peer.ReportChunk request,
        io.grpc.stub.StreamObserver<cds.distdownloader.proto.Ack> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReportMethod(), responseObserver);
    }

    /**
     * <pre>
     * Register peer and get current peer list in one round-trip.
     * </pre>
     */
    default void sendMessage(cds.distdownloader.proto.PeerEndpoint request,
        io.grpc.stub.StreamObserver<cds.distdownloader.v1.Tracker.AllPeers> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendMessageMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service Peer.
   * <pre>
   * peer can send message to tracker and receive acknowledgement; start tracking peer
   * </pre>
   */
  public static abstract class PeerImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return PeerGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service Peer.
   * <pre>
   * peer can send message to tracker and receive acknowledgement; start tracking peer
   * </pre>
   */
  public static final class PeerStub
      extends io.grpc.stub.AbstractAsyncStub<PeerStub> {
    private PeerStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PeerStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PeerStub(channel, callOptions);
    }

    /**
     * <pre>
     * Called once when a peer comes online so tracker can register it.
     * </pre>
     */
    public void register(cds.distdownloader.proto.PeerEndpoint request,
        io.grpc.stub.StreamObserver<cds.distdownloader.proto.Ack> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRegisterMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Called periodically so that tracker keeps this peer marked alive.
     * </pre>
     */
    public void beat(cds.distdownloader.proto.Peer.Heartbeat request,
        io.grpc.stub.StreamObserver<cds.distdownloader.proto.Ack> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getBeatMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Called after a peer gets a chunk so tracker updates chunk ownership.
     * </pre>
     */
    public void report(cds.distdownloader.proto.Peer.ReportChunk request,
        io.grpc.stub.StreamObserver<cds.distdownloader.proto.Ack> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReportMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Register peer and get current peer list in one round-trip.
     * </pre>
     */
    public void sendMessage(cds.distdownloader.proto.PeerEndpoint request,
        io.grpc.stub.StreamObserver<cds.distdownloader.v1.Tracker.AllPeers> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendMessageMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service Peer.
   * <pre>
   * peer can send message to tracker and receive acknowledgement; start tracking peer
   * </pre>
   */
  public static final class PeerBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<PeerBlockingStub> {
    private PeerBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PeerBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PeerBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Called once when a peer comes online so tracker can register it.
     * </pre>
     */
    public cds.distdownloader.proto.Ack register(cds.distdownloader.proto.PeerEndpoint request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRegisterMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Called periodically so that tracker keeps this peer marked alive.
     * </pre>
     */
    public cds.distdownloader.proto.Ack beat(cds.distdownloader.proto.Peer.Heartbeat request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getBeatMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Called after a peer gets a chunk so tracker updates chunk ownership.
     * </pre>
     */
    public cds.distdownloader.proto.Ack report(cds.distdownloader.proto.Peer.ReportChunk request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReportMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Register peer and get current peer list in one round-trip.
     * </pre>
     */
    public cds.distdownloader.v1.Tracker.AllPeers sendMessage(cds.distdownloader.proto.PeerEndpoint request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendMessageMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service Peer.
   * <pre>
   * peer can send message to tracker and receive acknowledgement; start tracking peer
   * </pre>
   */
  public static final class PeerFutureStub
      extends io.grpc.stub.AbstractFutureStub<PeerFutureStub> {
    private PeerFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PeerFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PeerFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Called once when a peer comes online so tracker can register it.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<cds.distdownloader.proto.Ack> register(
        cds.distdownloader.proto.PeerEndpoint request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRegisterMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Called periodically so that tracker keeps this peer marked alive.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<cds.distdownloader.proto.Ack> beat(
        cds.distdownloader.proto.Peer.Heartbeat request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getBeatMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Called after a peer gets a chunk so tracker updates chunk ownership.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<cds.distdownloader.proto.Ack> report(
        cds.distdownloader.proto.Peer.ReportChunk request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReportMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Register peer and get current peer list in one round-trip.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<cds.distdownloader.v1.Tracker.AllPeers> sendMessage(
        cds.distdownloader.proto.PeerEndpoint request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendMessageMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_REGISTER = 0;
  private static final int METHODID_BEAT = 1;
  private static final int METHODID_REPORT = 2;
  private static final int METHODID_SEND_MESSAGE = 3;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_REGISTER:
          serviceImpl.register((cds.distdownloader.proto.PeerEndpoint) request,
              (io.grpc.stub.StreamObserver<cds.distdownloader.proto.Ack>) responseObserver);
          break;
        case METHODID_BEAT:
          serviceImpl.beat((cds.distdownloader.proto.Peer.Heartbeat) request,
              (io.grpc.stub.StreamObserver<cds.distdownloader.proto.Ack>) responseObserver);
          break;
        case METHODID_REPORT:
          serviceImpl.report((cds.distdownloader.proto.Peer.ReportChunk) request,
              (io.grpc.stub.StreamObserver<cds.distdownloader.proto.Ack>) responseObserver);
          break;
        case METHODID_SEND_MESSAGE:
          serviceImpl.sendMessage((cds.distdownloader.proto.PeerEndpoint) request,
              (io.grpc.stub.StreamObserver<cds.distdownloader.v1.Tracker.AllPeers>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getRegisterMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              cds.distdownloader.proto.PeerEndpoint,
              cds.distdownloader.proto.Ack>(
                service, METHODID_REGISTER)))
        .addMethod(
          getBeatMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              cds.distdownloader.proto.Peer.Heartbeat,
              cds.distdownloader.proto.Ack>(
                service, METHODID_BEAT)))
        .addMethod(
          getReportMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              cds.distdownloader.proto.Peer.ReportChunk,
              cds.distdownloader.proto.Ack>(
                service, METHODID_REPORT)))
        .addMethod(
          getSendMessageMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              cds.distdownloader.proto.PeerEndpoint,
              cds.distdownloader.v1.Tracker.AllPeers>(
                service, METHODID_SEND_MESSAGE)))
        .build();
  }

  private static abstract class PeerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    PeerBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return cds.distdownloader.v1.Tracker.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Peer");
    }
  }

  private static final class PeerFileDescriptorSupplier
      extends PeerBaseDescriptorSupplier {
    PeerFileDescriptorSupplier() {}
  }

  private static final class PeerMethodDescriptorSupplier
      extends PeerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    PeerMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (PeerGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new PeerFileDescriptorSupplier())
              .addMethod(getRegisterMethod())
              .addMethod(getBeatMethod())
              .addMethod(getReportMethod())
              .addMethod(getSendMessageMethod())
              .build();
        }
      }
    }
    return result;
  }
}
