package com.yahooeu2k.dlb_charging;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

public class VehiclePropertyObserver {
    private static final String TAG = "DlbCharging.Vhal";

    private static final String GRPC_HOST = "localhost";
    private static final int GRPC_PORT = 40004;
    private static final String STREAM_METHOD_NAME = "vhal_proto.VehicleServer/StartPropertyValuesStream";
    private static final String SEND_ALL_METHOD = "vhal_proto.VehicleServer/SendAllPropertyValuesToStream";

    public interface VehiclePropertyListener {
        void onPropertyUpdated(int propId, int value);

        void onConnectionStateChanged(boolean connected);
    }

    private Context context;
    private final java.util.List<VehiclePropertyListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private ManagedChannel grpcChannel;
    private Thread connectThread;
    // volatile: written by connect thread, read by main thread via
    // setVehicleProperty
    private volatile io.grpc.stub.StreamObserver<vhal_proto.WrappedVehiclePropValue> setPropertyStream;

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final long RECONNECT_DELAY_MS = 3000;

    public static final int PROP_TURN_SIGNAL = 0x11400408; // 289408008
    public static final int PROP_DOOR_POS = 0x16400B00; // 373295872
    public static final int PROP_BATTERY_LVL = 0x11600309; // 291504905
    public static final int PROP_BATTERY_PCT = 0x2160730E; // 559969038
    public static final int PROP_CHG_CURRENT = 0x21607272; // 559968882
    public static final int PROP_CHG_CURRENT_POWER = 0x21607363; // 559969123
    public static final int PROP_PLUG_STATE = 0x21407289; // 557871753
    public static final int PROP_CHG_WORK_CURRENT = 0x2160728C; // 559968908
    public static final int PROP_CHARGE_FUNC_AC = 557872747; // AC charging on/off (0=stop, 1=start)
    public static final int PROP_CHG_VOLTAGE = 559968907; // Charging voltage (float, V)
    public static final int PROP_SPEED = 0x11600507; // 291504647

    /* Door area IDs */
    private static final int VHAL_AREA_FL = 1;
    private static final int VHAL_AREA_FR = 4;
    private static final int VHAL_AREA_WFL = 16;
    private static final int VHAL_AREA_WFR = 64;

    /* Turn signal values */
    private static final int VHAL_SIG_NONE = 0;
    private static final int VHAL_SIG_RIGHT = 1;
    private static final int VHAL_SIG_LEFT = 2;

    /* Door values */
    private static final int VHAL_DOOR_OPEN = 1;

    public VehiclePropertyObserver(Context context) {
        this.context = context.getApplicationContext();
    }

    public void addListener(VehiclePropertyListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(VehiclePropertyListener listener) {
        listeners.remove(listener);
    }

    public void setVehicleProperty(int propId, int value) {
        setVehicleProperty(propId, value, false);
    }

    public void setVehicleProperty(int propId, float value) {
        setVehicleProperty(propId, value, true);
    }

    private void setVehicleProperty(int propId, Object value, boolean isFloat) {
        if (setPropertyStream == null) {
            Log.w(TAG, "Cannot set property: VHAL stream not connected");
            return;
        }

        try {
            vhal_proto.VehiclePropValue.Builder propBuilder = vhal_proto.VehiclePropValue.newBuilder()
                    .setProp(propId)
                    .setTimestamp(0)
                    .setAreaId(0);

            if (isFloat) {
                propBuilder.addFloatValues((float) value);
            } else {
                propBuilder.addInt32Values((int) value);
            }

            vhal_proto.WrappedVehiclePropValue request = vhal_proto.WrappedVehiclePropValue
                    .newBuilder()
                    .setValue(propBuilder.build())
                    .setUpdateStatus(false)
                    .build();

            setPropertyStream.onNext(request);
            Log.d(TAG, "setVehicleProperty(" + propId + ", " + value + ") queued to stream");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send vehicle property " + propId + ": " + e.getMessage());
            // Stream is probably dead — null it so the next call gets the "not connected"
            // warning
            // instead of hammering a broken channel.
            setPropertyStream = null;
        }
    }

    public void start() {
        if (running)
            return;
        running = true;

        Log.i(TAG, "Starting VehiclePropertyObserver (pure Java decoder)...");
        connectThread = new Thread(this::connectLoop, "VHALConnectThread");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void connectLoop() {
        while (running) {
            try {
                Log.d(TAG, "Connecting to vehicle API service...");
                boolean ok = connect();
                if (ok) {
                    Log.d(TAG, "Connected, starting property stream");
                    notifyConnectionState(true);
                    streamProperties(); // blocks until disconnected
                }
            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
            }

            connected = false;
            notifyConnectionState(false);
            disconnect();

            if (!running)
                break;

            try {
                Log.d(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms...");
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private boolean connect() {
        try {
            String sessionId = UUID.randomUUID().toString();
            Metadata headers = new Metadata();
            headers.put(
                    Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER),
                    sessionId);
            headers.put(
                    Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER),
                    "dlb_charging_vhal");

            grpcChannel = OkHttpChannelBuilder.forAddress(GRPC_HOST, GRPC_PORT)
                    .usePlaintext()
                    .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .build();

            connected = true;
            Log.d(TAG, "Channel created, session_id=" + sessionId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    private void disconnect() {
        connected = false;

        if (setPropertyStream != null) {
            try {
                setPropertyStream.onCompleted();
            } catch (Exception ignored) {
            }
            setPropertyStream = null;
        }

        if (grpcChannel != null) {
            try {
                grpcChannel.shutdown();
                if (!grpcChannel.awaitTermination(2, TimeUnit.SECONDS)) {
                    grpcChannel.shutdownNow();
                }
            } catch (Exception ignored) {
                try {
                    grpcChannel.shutdownNow();
                } catch (Exception ignored2) {
                }
            }
            grpcChannel = null;
        }
    }

    private void streamProperties() {
        if (grpcChannel == null)
            return;

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        try {
            MethodDescriptor<byte[], byte[]> streamMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName(STREAM_METHOD_NAME)
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();

            ClientCall<byte[], byte[]> call = grpcChannel.newCall(streamMethod, CallOptions.DEFAULT);

            vhal_proto.VehicleServerGrpc.VehicleServerStub stub = vhal_proto.VehicleServerGrpc.newStub(grpcChannel);
            setPropertyStream = stub.setProperty(new io.grpc.stub.StreamObserver<vhal_proto.VehicleHalCallStatus>() {
                @Override
                public void onNext(vhal_proto.VehicleHalCallStatus status) {
                    Log.d(TAG, "SetProperty response: " + status.getStatusCode());
                }

                @Override
                public void onError(Throwable t) {
                    // Stream died (connection lost, server restart, etc.).
                    // Null it out immediately so setVehicleProperty() doesn't try
                    // to use a dead observer; connectLoop will re-establish it.
                    setPropertyStream = null;
                    Log.w(TAG, "SetProperty stream closed: " + t.getMessage());
                }

                @Override
                public void onCompleted() {
                    setPropertyStream = null;
                    Log.d(TAG, "SetProperty stream closed by server");
                }
            });

            ClientCalls.asyncServerStreamingCall(call, new byte[0], new StreamObserver<byte[]>() {
                @Override
                public void onNext(byte[] value) {
                    try {
                        processPropertyBatch(value);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to process property batch: " + e.getMessage());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    String msg = t.getMessage();
                    // UNAVAILABLE / ABORTED are expected when the channel is shut down
                    // intentionally (onPause / reconnect cycle) — log at warn, not error.
                    if (msg != null && (msg.contains("UNAVAILABLE") || msg.contains("ABORTED"))) {
                        Log.w(TAG, "Property stream closed: " + msg);
                    } else {
                        Log.e(TAG, "Property stream error: " + msg);
                    }
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    Log.d(TAG, "Property stream completed");
                    latch.countDown();
                }
            });

            new Thread(() -> {
                try {
                    if (grpcChannel != null) {
                        MethodDescriptor<byte[], byte[]> sendAllMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                                .setType(MethodDescriptor.MethodType.UNARY)
                                .setFullMethodName(SEND_ALL_METHOD)
                                .setRequestMarshaller(ByteMarshaller.INSTANCE)
                                .setResponseMarshaller(ByteMarshaller.INSTANCE)
                                .build();
                        ClientCall<byte[], byte[]> sendCall = grpcChannel.newCall(sendAllMethod, CallOptions.DEFAULT);
                        ClientCalls.blockingUnaryCall(sendCall, new byte[0]);
                        Log.d(TAG, "Requested all property values to stream");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "SendAll failed (non-fatal): " + e.getMessage());
                }
            }, "VehicleApiSendAll").start();

            latch.await();

        } catch (Exception e) {
            Log.e(TAG, "Stream setup failed: " + e.getMessage());
        }
    }

    /**
     * Decode a property batch using generated Java protobuf stubs.
     * Replaces the old native VhalNative.decode() call.
     *
     * Wire format (matches C two-level unwrap):
     * WrappedVehiclePropValues { repeated WrappedVehiclePropValue = 1 }
     * → WrappedVehiclePropValue { VehiclePropValue value = 1 }
     * → VehiclePropValue { prop, int32_values, float_values, ... }
     */
    private void processPropertyBatch(byte[] data) {
        if (data == null || data.length == 0)
            return;

        try {
            // Level 1: parse the outer batch
            vhal_proto.WrappedVehiclePropValues batch = vhal_proto.WrappedVehiclePropValues.parseFrom(data);

            List<vhal_proto.WrappedVehiclePropValue> wrappedValues = batch.getValuesList();

            for (vhal_proto.WrappedVehiclePropValue wrapped : wrappedValues) {
                // Level 2: unwrap to get actual VehiclePropValue
                if (!wrapped.hasValue())
                    continue;
                vhal_proto.VehiclePropValue prop = wrapped.getValue();

                int propId = prop.getProp();
                List<Integer> int32Values = prop.getInt32ValuesList();
                List<Float> floatValues = prop.getFloatValuesList();
                List<Long> int64Values = prop.getInt64ValuesList();
                int areaId = prop.getAreaId();

                switch (propId) {
                    case PROP_BATTERY_LVL:
                    case PROP_BATTERY_PCT:
                    case PROP_CHG_CURRENT:
                    case PROP_CHG_CURRENT_POWER:
                    case PROP_CHG_WORK_CURRENT:
                    case PROP_CHG_VOLTAGE:
                    case PROP_SPEED: {
                        // Float-valued properties
                        // C fallback chain: field 7 (float_values) → field 6 (int64_values as float)
                        float fVal = Float.NaN;
                        if (!floatValues.isEmpty()) {
                            fVal = floatValues.get(0);
                        } else if (!int64Values.isEmpty()) {
                            // Fallback: field 6 bytes reinterpreted as float (matches C field-6 fallback)
                            long raw = int64Values.get(0);
                            fVal = Float.intBitsToFloat((int) raw);
                        }
                        if (!Float.isNaN(fVal)) {
                            int bits = Float.floatToIntBits(fVal);
                            notifyProperty(propId, bits);
                        }
                        break;
                    }

                    case PROP_PLUG_STATE:
                    case PROP_CHARGE_FUNC_AC: {
                        // Integer-valued properties
                        if (!int32Values.isEmpty()) {
                            notifyProperty(propId, int32Values.get(0));
                        }
                        break;
                    }

                    case PROP_TURN_SIGNAL: {
                        if (!int32Values.isEmpty()) {
                            int sig = int32Values.get(0);
                            int dir = mapTurnSignal(sig);
                            notifyProperty(propId, dir);
                        }
                        break;
                    }

                    case PROP_DOOR_POS: {
                        if (!int32Values.isEmpty()) {
                            int doorVal = int32Values.get(0);
                            int doorPos = mapDoorArea(areaId);
                            if (doorPos > 0) {
                                int eventVal = (doorVal == VHAL_DOOR_OPEN) ? doorPos : -doorPos;
                                notifyProperty(propId, eventVal);
                            }
                        }
                        break;
                    }

                    default:
                        break;
                }
            }
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            Log.e(TAG, "Protobuf parse error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error processing property batch: " + e.getMessage());
        }
    }

    /** Dispatch a property update to all listeners on the main thread. */
    private void notifyProperty(int propId, int value) {
        mainHandler.post(() -> {
            for (VehiclePropertyListener listener : listeners) {
                listener.onPropertyUpdated(propId, value);
            }
        });
    }

    /**
     * Map VHAL turn signal value to abstract direction (0=none, 1=left, 2=right).
     */
    private static int mapTurnSignal(int vhalVal) {
        if (vhalVal == VHAL_SIG_LEFT)
            return 1; // DIR_LEFT
        if (vhalVal == VHAL_SIG_RIGHT)
            return 2; // DIR_RIGHT
        return 0; // DIR_NONE
    }

    /** Map VHAL area_id to abstract door position (1=FL, 2=FR, 3=RL, 4=RR). */
    private static int mapDoorArea(int areaId) {
        if (areaId == VHAL_AREA_FL)
            return 1;
        if (areaId == VHAL_AREA_FR)
            return 2;
        if (areaId == VHAL_AREA_WFL)
            return 3;
        if (areaId == VHAL_AREA_WFR)
            return 4;
        return 0;
    }

    private void notifyConnectionState(boolean isConnected) {
        mainHandler.post(() -> {
            for (VehiclePropertyListener listener : listeners) {
                listener.onConnectionStateChanged(isConnected);
            }
        });
    }

    public void stop() {
        running = false;
        disconnect();
        if (connectThread != null) {
            connectThread.interrupt();
            connectThread = null;
        }
        Log.i(TAG, "Stopped VehiclePropertyObserver");
    }

    private enum ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = stream.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                return baos.toByteArray();
            } catch (Exception e) {
                return new byte[0];
            }
        }
    }
}
