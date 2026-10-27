import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.File;
import java.net.*;

/** Run via app_process on Android 31+; no APK install, UI, settings or app data access. */
public final class SocketFdOwnershipProbe {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static int descriptors() { return new File("/proc/self/fd").list().length; }

    public static void main(String[] args) throws Exception {
        int before = descriptors();
        for (int i = 0; i < 100; i++) {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                listener.setSoTimeout(2000);
                Socket original = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(2000);
                    int fd;
                    try (ParcelFileDescriptor copy = ParcelFileDescriptor.fromSocket(original)) {
                        fd = copy.detachFd();
                    }
                    original.close();
                    try (ParcelFileDescriptor nativeOwner = ParcelFileDescriptor.adoptFd(fd)) {
                        check(Os.write(nativeOwner.getFileDescriptor(), new byte[]{42}, 0, 1) == 1, "dup TCP write");
                        check(peer.getInputStream().read() == 42, "Java close must not close native TCP copy");
                    }
                    check(peer.getInputStream().read() == -1, "native close must deliver TCP EOF");
                }
            }
            try (DatagramSocket peer = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                peer.setSoTimeout(2000);
                DatagramSocket original = new DatagramSocket();
                original.connect(InetAddress.getLoopbackAddress(), peer.getLocalPort());
                int fd;
                try (ParcelFileDescriptor copy = ParcelFileDescriptor.fromDatagramSocket(original)) {
                    fd = copy.detachFd();
                }
                original.close();
                try (ParcelFileDescriptor nativeOwner = ParcelFileDescriptor.adoptFd(fd)) {
                    check(Os.write(nativeOwner.getFileDescriptor(), new byte[]{43}, 0, 1) == 1, "dup UDP write");
                    DatagramPacket packet = new DatagramPacket(new byte[1], 1);
                    peer.receive(packet);
                    check(packet.getData()[0] == 43, "Java close must not close native UDP copy");
                }
            }
        }
        int after = descriptors();
        check(after <= before + 2, "FD leak: " + before + " -> " + after);
        System.out.println("PASS Android socket ownership: 100 TCP + 100 UDP handoffs, TCP EOF on native close, fdBefore=" + before + " fdAfter=" + after);
    }
}
