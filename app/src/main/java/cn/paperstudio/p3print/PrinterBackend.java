package cn.paperstudio.p3print;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.serialport.SerialPort;
import com.paperang.algorithm.utils.MMJImgAlgCore;
import com.paperang.mcu.power.McuPower;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class PrinterBackend implements AutoCloseable {
    public interface Progress { void report(String message, int percent); }
    protected final AppLog log;
    protected PrinterBackend(AppLog log) { this.log = log; }
    public abstract void print(Bitmap bitmap, Progress progress, AtomicBoolean cancelled) throws Exception;
    public abstract String name();
    public boolean mayStillUseBitmap() { return false; }
    @Override public abstract void close();

    public static IBinder findService() throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        Method getService = manager.getDeclaredMethod("getService", String.class);
        return (IBinder) getService.invoke(null, "mbprint");
    }

    public static PrinterBackend open(Context context, String mode, AppLog log) throws Exception {
        if ("auto".equals(mode) || "service".equals(mode)) {
            try {
                IBinder service = findService();
                if (service != null && service.isBinderAlive()) return new SystemPrinter(service, log);
            } catch (Exception error) {
                if ("service".equals(mode)) throw error;
                log.add("系统服务连接失败，尝试本机串口\n" + android.util.Log.getStackTraceString(error));
            }
            if ("service".equals(mode)) throw new IOException("系统 mbprint 服务不可用");
        }
        return new SerialPrinter(context, mode, log);
    }

    static boolean requestSerialLease(Context context, AppLog log) {
        try {
            context.sendBroadcast(new Intent("com.printer.intent.action.FACTORY_MODE_IN"));
            log.add("已发送串口释放请求");
            return true;
        } catch (SecurityException error) {
            log.add("系统限制工厂模式广播，继续使用应用已有的串口访问权限。" + error.getMessage());
            return false;
        }
    }

    static void releaseSerialLease(Context context, boolean requested, AppLog log) {
        if (!requested) return;
        try {
            context.sendBroadcast(new Intent("com.printer.intent.action.FACTORY_MODE_OUT"));
            log.add("已发送串口归还通知");
        } catch (SecurityException error) {
            log.add("系统拒绝串口归还广播。" + error.getMessage());
        }
    }

    /** Reconstructed Binder transactions from study.apk PrintManager and IMBPrintService. */
    public static final class SystemPrinter extends PrinterBackend {
        private static final String DESCRIPTOR = "com.paperang.printinterface.IMBPrintService";
        private final IBinder service;
        private long activeId = -1;
        private Binder activeCallback;
        private Binder activeBitmap;
        private volatile boolean bitmapPending;

        public SystemPrinter(IBinder service, AppLog log) throws Exception {
            super(log);
            this.service = service;
            log.add("系统打印服务 " + service.getInterfaceDescriptor());
            if (!DESCRIPTOR.equals(service.getInterfaceDescriptor())) throw new IOException("系统打印服务接口不匹配");
            int width = queryInt(12);
            log.add("打印头宽度 " + width);
            if (width > 0 && width != Protocol.WIDTH) throw new IOException("当前打印头为 " + width + " 点，需要 P3 Pro 864 点打印头");
        }

        private int queryInt(int code) throws RemoteException {
            Parcel request = Parcel.obtain();
            Parcel response = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                transact(code, request, response);
                return response.readInt();
            } finally { request.recycle(); response.recycle(); }
        }

        int getPrintState() throws RemoteException { return queryInt(4); }

        private void transact(int code, Parcel request, Parcel response) throws RemoteException {
            if (!service.transact(code, request, response, 0)) throw new RemoteException("不支持的打印服务事务 " + code);
            response.readException();
        }

        @Override public String name() { return "系统 mbprint 服务"; }
        @Override public boolean mayStillUseBitmap() { return bitmapPending; }

        @Override public void print(final Bitmap bitmap, Progress progress, AtomicBoolean cancelled) throws Exception {
            if (cancelled.get()) throw new IOException("已停止后续打印");
            checkState();
            CountDownLatch finished = new CountDownLatch(1);
            int[] result = {Integer.MIN_VALUE};
            activeBitmap = new Binder() {
                { attachInterface(null, "com.paperang.printinterface.IRemoteGetBitmap"); }
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == INTERFACE_TRANSACTION) { reply.writeString("com.paperang.printinterface.IRemoteGetBitmap"); return true; }
                    if (code != 1) return super.onTransact(code, data, reply, flags);
                    data.enforceInterface("com.paperang.printinterface.IRemoteGetBitmap");
                    reply.writeNoException();
                    reply.writeInt(1);
                    bitmap.writeToParcel(reply, 1);
                    log.add("系统已取走位图 " + bitmap.getWidth() + "×" + bitmap.getHeight());
                    return true;
                }
            };
            activeCallback = new Binder() {
                { attachInterface(null, "com.paperang.printinterface.IMBPrintCallBack"); }
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == INTERFACE_TRANSACTION) { reply.writeString("com.paperang.printinterface.IMBPrintCallBack"); return true; }
                    if (code < 1 || code > 4) return super.onTransact(code, data, reply, flags);
                    data.enforceInterface("com.paperang.printinterface.IMBPrintCallBack");
                    int value = code == 1 ? 0 : data.readInt();
                    log.add("系统回调 " + code + " 值 " + value);
                    if (code == 1) progress.report("系统开始打印", 0);
                    if (code == 2) progress.report("系统打印中", Math.max(0, Math.min(100, value)));
                    if (code == 3) progress.report("系统图像处理回调 " + value, 0);
                    if (code == 4) { bitmapPending = false; result[0] = value; finished.countDown(); }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
            };
            Parcel request = Parcel.obtain();
            Parcel response = Parcel.obtain();
            try {
                Bundle bundle = new Bundle();
                bundle.putBinder("bitmap", activeBitmap);
                request.writeInterfaceToken(DESCRIPTOR);
                request.writeInt(1);
                bundle.writeToParcel(request, 0);
                request.writeInt(0); // DENSITY_MODERATE, as PrintManager.printBitmap(bitmap, copies).
                request.writeInt(1);
                // A transaction failure after this point may still have submitted a job. Never retry it automatically.
                bitmapPending = true;
                transact(1, request, response);
                activeId = response.readLong();
                log.add("系统任务 id " + activeId);
                if (activeId < 0) { bitmapPending = false; throw new IOException("系统拒绝打印任务 " + activeId); }
            } finally { request.recycle(); response.recycle(); }
            request = Parcel.obtain();
            response = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                request.writeLong(activeId);
                request.writeStrongBinder(activeCallback);
                transact(5, request, response);
                progress.report("已提交系统，等待打印回调", 0);
                long deadline = System.currentTimeMillis() + 180000;
                while (!finished.await(1, TimeUnit.SECONDS)) {
                    if (!service.isBinderAlive()) throw new IOException("打印服务已断开，当前页状态待确认");
                    if (System.currentTimeMillis() >= deadline) throw new IOException("系统任务 " + activeId + " 等待回调超时，当前页可能已打印，请检查出纸后再操作");
                }
                if (result[0] != 0) throw new IOException("系统结束回调返回 " + result[0] + "，请检查设备，诊断日志已记录");
                progress.report("系统结束回调返回 0", 100);
            } finally {
                request.recycle(); response.recycle();
                unregister();
            }
        }

        private void checkState() throws Exception {
            int state;
            try {
                state = getPrintState();
            } catch (RemoteException | RuntimeException error) {
                log.add("系统状态查询失败，继续提交原客户端的位图打印请求\n" + android.util.Log.getStackTraceString(error));
                return;
            }
            log.add("系统打印状态 0x" + Integer.toHexString(state));
            String error = "";
            if ((state & 16) != 0) error += "缺纸 ";
            if ((state & 256) != 0) error += "过热 ";
            if ((state & 4096) != 0) error += "纸盖未合上 ";
            if ((state & 65536) != 0) error += "温度过低 ";
            if (!error.isEmpty()) throw new IOException(error.trim());
        }

        private void unregister() {
            if (activeCallback == null) return;
            Parcel request = Parcel.obtain();
            Parcel response = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                request.writeStrongBinder(activeCallback);
                transact(6, request, response);
            } catch (Exception error) { log.add("取消回调监听 " + error); }
            finally { request.recycle(); response.recycle(); }
            activeId = -1;
            activeCallback = null;
            activeBitmap = null;
        }

        @Override public void close() { unregister(); }
    }

    public static final class SerialPrinter extends PrinterBackend {
        private final Context context;
        private SerialPort port;
        private volatile int status = -1;
        private volatile long statusVersion;
        private volatile boolean closed;
        private final Object statusLock = new Object();
        private Thread reader;
        private boolean released;
        private String path;

        public SerialPrinter(Context context, String mode, AppLog log) throws Exception {
            super(log);
            this.context = context;
            boolean mobile = "serial4g".equals(mode) || ("auto".equals(mode) && Build.FINGERPRINT.contains("SPRD"));
            path = mobile ? "/dev/ttyS1" : "/dev/ttyMT0";
            int baud = mobile ? 460800 : 921600;
            try {
                released = requestSerialLease(context, log);
                Thread.sleep(60);
                if (!mobile) McuPower.on();
                port = new SerialPort(path, baud);
                log.add("串口打开 " + path + " @ " + baud);
                reader = new Thread(this::read, "P3-serial-read");
                reader.setDaemon(true);
                reader.start();
                queryStatus();
            } catch (Exception | LinkageError error) {
                close();
                throw new IOException("串口准备失败 " + path + "。" + error.getMessage(), error);
            }
        }

        @Override public String name() { return "原生串口 " + path; }

        private void read() {
            SerialPort readingPort = port;
            Protocol.Decoder decoder = new Protocol.Decoder();
            byte[] buffer = new byte[4096];
            try {
                while (!closed) {
                    int available = readingPort.input().available();
                    if (available == 0) { Thread.sleep(20); continue; }
                    int count = readingPort.input().read(buffer, 0, Math.min(buffer.length, available));
                    if (count < 0) break;
                    log.add("RX 字节 " + Protocol.hex(Arrays.copyOf(buffer, count), 96));
                    for (Protocol.ReceivedFrame frame : decoder.accept(buffer, count)) {
                        if (frame.command == 0x0d && frame.payload.length > 0) {
                            synchronized (statusLock) {
                                status = frame.payload[0] & 255;
                                statusVersion++;
                                statusLock.notifyAll();
                            }
                            String error = Protocol.statusError(status);
                            log.add("RX 帧 " + Integer.toHexString(frame.head) + "，状态 0x" + Integer.toHexString(status) + " " + (status == 0 ? "正常" : error));
                        }
                    }
                }
            } catch (IOException | InterruptedException error) { if (!closed) log.add("串口接收失败 " + error); }
        }

        private void queryStatus() throws Exception {
            long previous = statusVersion;
            byte[] query = Protocol.frame(0x0c, 0, new byte[]{1});
            log.add("TX 状态查询 " + Protocol.hex(query, 64));
            port.output().write(query);
            port.output().flush();
            synchronized (statusLock) {
                long deadline = android.os.SystemClock.elapsedRealtime() + 1500;
                while (statusVersion == previous && android.os.SystemClock.elapsedRealtime() < deadline) statusLock.wait(100);
            }
            if (statusVersion == previous) {
                log.add("状态查询暂未收到回复，按原 APK 示例继续发送。系统服务可能也在读取串口");
                return;
            }
            String error = Protocol.statusError(status);
            if (!error.isEmpty()) throw new IOException(error);
        }

        @Override public void print(Bitmap bitmap, Progress progress, AtomicBoolean cancelled) throws Exception {
            queryStatus();
            byte[] raster = MMJImgAlgCore.raster(bitmap);
            if (raster == null || raster.length == 0) throw new IOException("原厂图片算法返回空数据");
            log.add("原厂栅格 " + bitmap.getWidth() + "×" + bitmap.getHeight() + " -> " + raster.length + " bytes");
            int index = 0;
            for (int offset = 0; offset < raster.length; offset += Protocol.PAYLOAD_SIZE) {
                if (cancelled.get()) throw new IOException("已停止发送，已发送内容可能继续打印");
                byte[] packet = Protocol.frame(0, index, Protocol.payload(raster, offset));
                if (index == 0) log.add("TX 图像首包 " + Protocol.hex(packet, 24));
                port.output().write(packet);
                port.output().flush();
                progress.report("正在发送打印数据", Math.min(99, (offset + Protocol.PAYLOAD_SIZE) * 100 / raster.length));
                Thread.sleep(200);
                index++;
                if (index % 8 == 0) queryStatus();
            }
            byte[] feed = Protocol.feed();
            log.add("TX 走纸 " + Protocol.hex(feed, 64));
            port.output().write(feed);
            port.output().flush();
            progress.report("数据与走纸指令已发送", 100);
            log.add("串口发送结束，共 " + index + " 包。协议没有纸面完成确认");
        }

        @Override public void close() {
            closed = true;
            if (port != null) { port.closePort(); port = null; }
            if (reader != null) { reader.interrupt(); reader = null; }
            releaseSerialLease(context, released, log);
            released = false;
        }
    }
}
