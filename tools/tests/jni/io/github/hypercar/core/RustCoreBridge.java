package io.github.hypercar.core;

import android.os.ParcelFileDescriptor;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated app_process smoke test for the rebuilt Android library and JNI callback ABI. */
public final class RustCoreBridge {
    public interface EventListener {
        void onState(long generation, String state, String detail);
        void onLog(long generation, String line);
        void onIap(long generation, byte[] bytes);
        void onStats(long generation, int fps, long frames, long kbps, boolean audio, boolean ducking);
    }
    public interface SocketProvider {
        int openTcpFd(String host, int port, int timeoutMs);
        int openUdpFd(String host, int port);
    }
    public static native void nativeInitLogger();
    public static native void nativeSetListener(EventListener listener);
    public static native boolean nativeStart(String config, int controlFd, SocketProvider provider, long generation);
    public static native void nativeStop();
    public static native boolean nativeSendIap(long generation, byte[] bytes);
    public static native boolean nativeIsRunning();

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        nativeInitLogger();
        AtomicInteger unexpectedSocketRequests = new AtomicInteger();
        SocketProvider provider = new SocketProvider() {
            public int openTcpFd(String host, int port, int timeout) { unexpectedSocketRequests.incrementAndGet(); return -1; }
            public int openUdpFd(String host, int port) { unexpectedSocketRequests.incrementAndGet(); return -1; }
        };
        for (long round = 1; round <= 10; round++) {
            final long generation = round;
            CountDownLatch stopped = new CountDownLatch(1);
            AtomicInteger states = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            nativeSetListener(new EventListener() {
                public void onState(long id, String state, String detail) {
                    if (id != generation || !state.equals("starting")) failures.incrementAndGet();
                    states.incrementAndGet();
                    nativeStop();
                    stopped.countDown();
                }
                public void onLog(long id, String line) {
                    if (id != 0 && id != generation) failures.incrementAndGet();
                }
                public void onStats(long id, int fps, long frames, long kbps, boolean audio, boolean ducking) {
                    failures.incrementAndGet();
                }
                public void onIap(long id, byte[] bytes) { failures.incrementAndGet(); }
            });
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Socket original = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(3000);
                    int fd;
                    try (ParcelFileDescriptor copy = ParcelFileDescriptor.fromSocket(original)) { fd = copy.detachFd(); }
                    original.close();
                    String config = "{\"device_id\":\"02:00:00:00:00:00\",\"car_ip\":\"127.0.0.1\",\"airplay_port\":1,"
                        + "\"width\":1920,\"height\":720,\"fps\":30,\"enable_audio\":false,"
                        + "\"md_host\":\"256.256.256.256\",\"identity_path\":\"/data/local/tmp/hypercar-unused-probe-identity.txt\"}";
                    check(nativeStart(config, fd, provider, generation), "nativeStart false");
                    check(stopped.await(3, TimeUnit.SECONDS), "starting callback signature or stop deadlock");
                    check(!nativeIsRunning(), "engine still running after stop");
                    check(!nativeSendIap(generation, new byte[]{1}), "cancelled generation accepted iAP bytes");
                    check(peer.getInputStream().read() == -1, "control FD not released");
                    Thread.sleep(30);
                    check(states.get() == 1 && failures.get() == 0, "stale or malformed callback");
                }
            }
        }
        nativeSetListener(null);
        check(unexpectedSocketRequests.get() == 0, "cancelled startup still opened extra sockets");
        System.out.println("PASS Android JNI lifecycle: 10 start/stop generations, callback ABI, reentrant stop, control EOF, zero extra sockets");
    }
}
