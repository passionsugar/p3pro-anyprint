package cn.paperstudio.p3print;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class DocumentRenderer {
    private final Activity activity;
    private final WebView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private int[] breaks;

    public DocumentRenderer(Activity activity, WebView view) {
        this.activity = activity;
        this.view = view;
        density = activity.getResources().getDisplayMetrics().density;
    }

    public void prepare(String html, int fontSize, MainActivity.LocalClient localClient) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        long deadline = System.currentTimeMillis() + 35000;
        activity.runOnUiThread(() -> {
            view.setWebViewClient(new MainActivity.LocalClient(activity, localClient.store, localClient.previews) {
                @Override public void onPageFinished(WebView webView, String url) {
                    if (!url.endsWith("/render.html")) return;
                    String script = "loadPrintDocument(" + JSONObject.quote(html).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + "," + fontSize + ")";
                    webView.evaluateJavascript(script, ignored -> poll(deadline, loaded, failure));
                }
            });
            view.loadUrl(MainActivity.ORIGIN + "/render.html");
        });
        if (!loaded.await(40, TimeUnit.SECONDS)) throw new IOException("文档排版超时，请缩短内容后重试");
        if (failure.get() != null) throw failure.get();
    }

    private void poll(long deadline, CountDownLatch loaded, AtomicReference<Exception> failure) {
        view.evaluateJavascript("JSON.stringify({ready:!!window.printReady,error:window.printFailure||'',breaks:window.printBreaks||[]})", result -> {
            try {
                String value = (String) new org.json.JSONTokener(result).nextValue();
                JSONObject data = new JSONObject(value);
                if (!data.getString("error").isEmpty()) throw new IOException(data.getString("error"));
                if (data.getBoolean("ready")) {
                    JSONArray values = data.getJSONArray("breaks");
                    breaks = new int[values.length()];
                    for (int index = 0; index < breaks.length; index++) breaks[index] = values.getInt(index);
                    if (breaks.length < 2) throw new IOException("文档为空");
                    loaded.countDown();
                } else if (System.currentTimeMillis() > deadline) throw new IOException("字体或图片加载超时");
                else main.postDelayed(() -> poll(deadline, loaded, failure), 150);
            } catch (Exception error) { failure.set(error); loaded.countDown(); }
        });
    }

    public int pages() { return breaks.length - 1; }

    public Bitmap capture(int page) throws Exception {
        CountDownLatch captured = new CountDownLatch(1);
        AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        activity.runOnUiThread(() -> {
            try {
                int top = breaks[page];
                int height = Math.max(1, (breaks[page + 1] - top) * 2);
                Bitmap output = Bitmap.createBitmap(Protocol.WIDTH, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(output);
                canvas.drawColor(Color.WHITE);
                float scale = Protocol.WIDTH / (432f * density);
                canvas.scale(scale, scale);
                canvas.translate(0, -top * density);
                view.draw(canvas);
                bitmap.set(output);
            } catch (Exception error) { failure.set(error); }
            finally { captured.countDown(); }
        });
        if (!captured.await(15, TimeUnit.SECONDS)) throw new IOException("文档截图超时");
        if (failure.get() != null) throw failure.get();
        return bitmap.get();
    }
}
