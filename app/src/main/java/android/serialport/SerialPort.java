package android.serialport;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import android.system.Os;
import android.system.ErrnoException;
import android.util.Log;

/** JNI ABI required by the serial library supplied in the user's APK. */
public final class SerialPort {
    private FileDescriptor mFd;
    private final FileInputStream input;
    private final FileOutputStream output;
    private static native FileDescriptor open(String path, int baud, int flags);

    static { System.loadLibrary("serial_port"); }

    public SerialPort(String path, int baud) throws IOException {
        File device = new File(path);
        if (!device.canRead() || !device.canWrite()) {
            throw new IOException("串口无读写权限 " + path + "，请使用系统服务模式或设备厂商授权");
        }
        mFd = open(path, baud, 0);
        if (mFd == null) throw new IOException("串口打开失败 " + path);
        input = new FileInputStream(mFd);
        output = new FileOutputStream(mFd);
    }

    public FileInputStream input() { return input; }
    public FileOutputStream output() { return output; }
    public void closePort() {
        // Android's descriptor-aware close also invalidates the shared FileDescriptor.
        try { Os.close(mFd); }
        catch (ErrnoException error) { Log.w("P3Serial", "Closing serial descriptor", error); }
    }
}
