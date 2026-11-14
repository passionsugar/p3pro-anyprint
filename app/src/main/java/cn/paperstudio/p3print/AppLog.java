package cn.paperstudio.p3print;

import android.os.Build;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppLog {
    public static final String VERSION = "1.0.1";
    private final StringBuilder entries = new StringBuilder();

    public AppLog() {
        add("随心打印 " + VERSION + " / Android " + Build.VERSION.RELEASE + " / " + Build.MODEL + " / UID " + android.os.Process.myUid());
        add("Fingerprint " + Build.FINGERPRINT);
    }

    public synchronized void add(String message) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + message;
        Log.i("P3AnyPrint", line);
        entries.append(line).append('\n');
        if (entries.length() > 64000) entries.delete(0, entries.length() - 48000);
    }

    public synchronized String text() { return entries.toString(); }
}
