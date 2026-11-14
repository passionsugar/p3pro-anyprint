package cn.paperstudio.p3print;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Environment;
import android.content.pm.PackageManager;
import android.Manifest;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    public static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int PICK_FILE = 20;
    private static final int EXPORT_LOG = 21;
    private static final int STORAGE_PERMISSION = 22;
    WebView web;
    private WebView renderView;
    private ImportStore imports;
    private AppLog log;
    private SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean printing = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Map<String, File> previews = new ConcurrentHashMap<>();
    private String pickerTarget = "documents";
    private final List<Uri> localSelection = new ArrayList<>();
    private volatile boolean destroyed;
    private LocalClient localClient;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        WebView.enableSlowWholeDocumentDraw();
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        log = new AppLog();
        imports = new ImportStore(this);
        localClient = new LocalClient(this, imports, previews);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        configure(web);
        web.addJavascriptInterface(new Bridge(), "Android");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        renderView = new WebView(this);
        configure(renderView);
        renderView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        float density = getResources().getDisplayMetrics().density;
        root.addView(renderView, new FrameLayout.LayoutParams(Math.round(432 * density), Math.round(1600 * density)));
        renderView.setTranslationX(-100000);
        setContentView(root);
        web.loadUrl(ORIGIN + "/index.html");
    }

    private void configure(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setTextZoom(100);
        settings.setSupportZoom(false);
        settings.setDefaultTextEncodingName("UTF-8");
        view.setWebViewClient(localClient);
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                log.add("Web " + message.messageLevel() + " " + message.sourceId() + ":" + message.lineNumber() + " " + message.message());
                return true;
            }
        });
    }

    static class LocalClient extends WebViewClient {
        final Activity activity;
        final ImportStore store;
        final Map<String, File> previews;
        LocalClient(Activity activity, ImportStore store, Map<String, File> previews) {
            this.activity = activity; this.store = store; this.previews = previews;
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return true; }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return resource(request.getUrl()); }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) { return resource(Uri.parse(url)); }
        private WebResourceResponse resource(Uri uri) {
            try {
                if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost())) return missing();
                String path = uri.getPath();
                if (path == null || path.contains("..") || path.contains("\\")) return missing();
                if (path.startsWith("/files/")) {
                    ImportStore.Item item = store.get(path.substring(7));
                    return new WebResourceResponse(item.mime, null, new FileInputStream(item.file));
                }
                if (path.startsWith("/thumb/")) {
                    ImportStore.Item item = store.get(path.substring(7));
                    if (item.thumbnail == null) return missing();
                    return new WebResourceResponse("image/png", null, new FileInputStream(item.thumbnail));
                }
                if (path.startsWith("/preview/")) {
                    File file = previews.get(path.substring(9));
                    if (file == null) return missing();
                    return new WebResourceResponse("image/png", null, new FileInputStream(file));
                }
                String mime = "text/plain";
                if (path.endsWith(".html")) mime = "text/html";
                if (path.endsWith(".js")) mime = "application/javascript";
                if (path.endsWith(".css")) mime = "text/css";
                if (path.endsWith(".woff2")) mime = "font/woff2";
                if (path.endsWith(".woff")) mime = "font/woff";
                if (path.endsWith(".ttf")) mime = "font/ttf";
                return new WebResourceResponse(mime, "UTF-8", activity.getAssets().open(path.substring(1)));
            } catch (Exception error) { return missing(); }
        }
        private WebResourceResponse missing() {
            WebResourceResponse response = new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            response.setStatusCodeAndReasonPhrase(404, "Not Found");
            return response;
        }
    }

    private void emit(String event, JSONObject data) {
        if (destroyed) return;
        String script = "window.onNativeEvent&&window.onNativeEvent(" + JSONObject.quote(event) + "," + data.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        runOnUiThread(() -> { if (!destroyed) web.evaluateJavascript(script, null); });
    }

    private void error(Throwable error) {
        log.add("失败 " + error);
        try { emit("error", new JSONObject().put("message", userMessage(error))); } catch (Exception ignored) {}
    }
    private static String userMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    public final class Bridge {
        @JavascriptInterface public void ready() {
            try { emit("ready", new JSONObject().put("mode", preferences.getString("mode", "auto"))); } catch (Exception error) { error(error); }
            inspectDevice();
        }
        @JavascriptInterface public void chooseFiles(String target) { runOnUiThread(() -> pick(target)); }
        @JavascriptInterface public void setMode(String mode) {
            if (Arrays.asList("auto", "service", "serialwifi", "serial4g").contains(mode)) {
                preferences.edit().putString("mode", mode).apply(); log.add("接口模式 " + mode);
            }
        }
        @JavascriptInterface public void inspectDevice() { worker.execute(MainActivity.this::inspect); }
        @JavascriptInterface public void commitRaster(String id, String dataUrl) {
            worker.execute(() -> {
                try { imports.commitRaster(id, dataUrl); emit("rasterReady", imports.get(id).json()); }
                catch (Exception | OutOfMemoryError error) { error(error); }
            });
        }
        @JavascriptInterface public void previewPdf(String id, int page) {
            worker.execute(() -> {
                try {
                    ImportStore.Item item = imports.get(id);
                    if (!"pdf".equals(item.kind) || page < 0 || page >= item.pages) throw new IOException("PDF 页码无效");
                    Bitmap bitmap = imports.pdfPage(item, page, 432);
                    String key = UUID.randomUUID().toString();
                    File preview = new File(getCacheDir(), "preview-" + key + ".png");
                    try (FileOutputStream output = new FileOutputStream(preview)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
                    finally { bitmap.recycle(); }
                    previews.put(key, preview);
                    emit("pdfPreview", new JSONObject().put("id", id).put("index", page).put("url", "/preview/" + key));
                } catch (Exception | OutOfMemoryError error) { error(error); }
            });
        }
        @JavascriptInterface public void printJob(String json) {
            if (!printing.compareAndSet(false, true)) { error(new IOException("已有打印任务正在执行")); return; }
            cancelled.set(false);
            runOnUiThread(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            worker.execute(() -> runJob(json));
        }
        @JavascriptInterface public void cancelPrint() { cancelled.set(true); log.add("用户停止后续打印"); }
        @JavascriptInterface public void copyDiagnostics() {
            runOnUiThread(() -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("P3Pro 打印诊断", log.text()));
                try { emit("error", new JSONObject().put("message", "诊断已复制，可粘贴发送")); }
                catch (Exception error) { error(error); }
            });
        }
        @JavascriptInterface public void exportDiagnostics() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/plain"); intent.putExtra(Intent.EXTRA_TITLE, "P3Pro-打印诊断.txt");
                    startActivityForResult(intent, EXPORT_LOG);
                } catch (ActivityNotFoundException error) { error(new IOException("系统没有文件保存器，诊断信息可在设置中查看")); }
            });
        }
    }

    private void pick(String target) {
        if (!Arrays.asList("images", "documents", "text").contains(target)) return;
        pickerTarget = target;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if ("images".equals(target)) { intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/octet-stream"}); }
        else intent.putExtra(Intent.EXTRA_MIME_TYPES, "text".equals(target) ? new String[]{"text/*", "application/octet-stream"} : new String[]{"application/pdf", "text/*", "application/octet-stream"});
        try { startActivityForResult(intent, PICK_FILE); }
        catch (ActivityNotFoundException error) {
            intent.setAction(Intent.ACTION_GET_CONTENT);
            try { startActivityForResult(intent, PICK_FILE); }
            catch (ActivityNotFoundException second) { openLocalPicker(); }
        }
    }

    private void openLocalPicker() {
        localSelection.clear();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
            return;
        }
        showLocalDirectory(Environment.getExternalStorageDirectory());
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == STORAGE_PERMISSION) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) showLocalDirectory(Environment.getExternalStorageDirectory());
            else error(new IOException("读取本机文件需要存储权限，可以在应用设置中授权"));
        }
    }

    private void showLocalDirectory(File requested) {
        try {
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            File directory = requested.getCanonicalFile();
            if (!directory.equals(root) && !directory.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("文件目录超出共享存储范围");
            File[] children = directory.listFiles(file -> file.isDirectory() || localFileSupported(file.getName()));
            if (children == null) throw new IOException("当前目录不可读取");
            Arrays.sort(children, (first, second) -> {
                if (first.isDirectory() != second.isDirectory()) return first.isDirectory() ? -1 : 1;
                return first.getName().compareToIgnoreCase(second.getName());
            });
            List<File> entries = new ArrayList<>();
            if (!directory.equals(root)) entries.add(directory.getParentFile());
            entries.addAll(Arrays.asList(children));
            String[] names = new String[entries.size()];
            boolean[] checked = new boolean[entries.size()];
            for (int index = 0; index < entries.size(); index++) {
                File file = entries.get(index);
                names[index] = file.equals(directory.getParentFile()) ? "↑ 上一级" : (file.isDirectory() ? "▸ " : "") + file.getName();
                checked[index] = localSelection.contains(Uri.fromFile(file));
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("本机文件 · " + directory.getName()).setNegativeButton("取消", null);
            if ("images".equals(pickerTarget)) {
                builder.setMultiChoiceItems(names, checked, (dialog, index, selected) -> {
                    File file = entries.get(index);
                    if (file.isDirectory()) { dialog.dismiss(); showLocalDirectory(file); }
                    else if (selected) localSelection.add(Uri.fromFile(file));
                    else localSelection.remove(Uri.fromFile(file));
                });
                builder.setPositiveButton("导入已选图片", (dialog, which) -> {
                    List<Uri> selected = new ArrayList<>(localSelection);
                    if (selected.isEmpty()) { error(new IOException("尚未选择图片")); return; }
                    String target = pickerTarget;
                    worker.execute(() -> importFiles(selected, target));
                });
            } else builder.setItems(names, (dialog, index) -> {
                File file = entries.get(index);
                if (file.isDirectory()) showLocalDirectory(file);
                else { String target = pickerTarget; worker.execute(() -> importFiles(java.util.Collections.singletonList(Uri.fromFile(file)), target)); }
            });
            builder.show();
        } catch (Exception error) { error(error); }
    }

    private boolean localFileSupported(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if ("images".equals(pickerTarget)) return lower.matches(".*\\.(jpg|jpeg|png|webp|bmp|gif|tif|tiff|svg|heic|heif|avif)$");
        return lower.matches(".*\\.(txt|md|markdown)$") || ("documents".equals(pickerTarget) && lower.endsWith(".pdf"));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == EXPORT_LOG && data.getData() != null) {
            Uri uri = data.getData();
            worker.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("无法保存诊断文件");
                    output.write(log.text().getBytes(StandardCharsets.UTF_8));
                    emit("error", new JSONObject().put("message", "诊断文件已保存"));
                } catch (Exception error) { error(error); }
            });
            return;
        }
        if (requestCode != PICK_FILE) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int index = 0; index < Math.min(clip.getItemCount(), 100); index++) uris.add(clip.getItemAt(index).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        final String target = pickerTarget;
        worker.execute(() -> importFiles(uris, target));
    }

    private void importFiles(List<Uri> uris, String target) {
        JSONArray selected = new JSONArray();
        JSONArray errors = new JSONArray();
        for (Uri uri : uris) {
            try {
                ImportStore.Item item = imports.add(uri);
                if ("images".equals(target) && !Arrays.asList("image", "special").contains(item.kind)) throw new IOException(item.name + " 不是图片");
                if ("text".equals(target) && !Arrays.asList("md", "txt").contains(item.kind)) throw new IOException("文本编辑器仅导入 TXT 和 Markdown");
                selected.put(item.json());
                log.add("导入 " + item.name + " " + item.kind + " " + item.width + "×" + item.height);
            } catch (Exception | OutOfMemoryError error) { log.add("导入失败 " + error); errors.put(userMessage(error)); }
        }
        try { emit("imported", new JSONObject().put("target", target).put("items", selected).put("errors", errors)); }
        catch (Exception error) { error(error); }
    }

    private void inspect() {
        StringBuilder details = new StringBuilder("随心打印 " + AppLog.VERSION + " / UID " + android.os.Process.myUid() + "\nAndroid " + Build.VERSION.RELEASE + " / " + Build.MODEL + "\n");
        boolean available = false;
        try {
            IBinder service = PrinterBackend.findService();
            if (service != null) {
                details.append("mbprint ").append(service.getInterfaceDescriptor()).append('\n');
                available = service.isBinderAlive();
                try (PrinterBackend.SystemPrinter printer = new PrinterBackend.SystemPrinter(service, log)) {
                    details.append("服务查询成功，状态 0x").append(Integer.toHexString(printer.getPrintState())).append('\n');
                } catch (Exception error) {
                    details.append("服务查询失败 ").append(userMessage(error)).append('\n');
                    log.add("系统服务诊断\n" + android.util.Log.getStackTraceString(error));
                }
            }
            else details.append("mbprint 服务未发现\n");
        } catch (Exception error) { details.append("mbprint ").append(userMessage(error)).append('\n'); }
        for (String path : new String[]{"/dev/ttyMT0", "/dev/ttyS1"}) {
            File device = new File(path);
            details.append(path).append(device.exists() ? (device.canRead() && device.canWrite() ? " 可读写" : " 权限受限") : " 不存在").append('\n');
            available |= device.canRead() && device.canWrite();
        }
        details.append("接口发现仅供诊断，出纸效果需要实机确认");
        log.add(details.toString());
        try { emit("device", new JSONObject().put("available", available).put("details", details.toString())); }
        catch (Exception error) { error(error); }
    }

    public static int[] parsePages(String range, int total) throws IOException {
        if (total < 1 || total > 500) throw new IOException("PDF 页数应在 1 到 500 之间，请先分割文档");
        LinkedHashSet<Integer> pages = new LinkedHashSet<>();
        if (range == null || range.trim().isEmpty()) for (int page = 0; page < total; page++) pages.add(page);
        else {
            for (String part : range.replace('，', ',').split(",", -1)) {
                String value = part.trim();
                if (!value.matches("[0-9]+(?:\\s*-\\s*[0-9]+)?")) throw new IOException("页码格式无效，请使用 1-3,5");
                String[] bounds = value.split("-");
                try {
                    int first = Integer.parseInt(bounds[0].trim());
                    int last = bounds.length == 1 ? first : Integer.parseInt(bounds[1].trim());
                    if (first < 1 || last > total || last < first) throw new IOException("页码超出范围 1-" + total);
                    for (int page = first; page <= last; page++) pages.add(page - 1);
                } catch (NumberFormatException error) { throw new IOException("页码数值过大"); }
            }
        }
        int[] values = new int[pages.size()]; int index = 0;
        for (int page : pages) values[index++] = page;
        return values;
    }

    static Bitmap padShortImage(Bitmap bitmap) {
        if (bitmap.getHeight() >= 32) return bitmap;
        Bitmap padded = Bitmap.createBitmap(bitmap.getWidth(), 32, Bitmap.Config.ARGB_8888);
        padded.eraseColor(android.graphics.Color.WHITE);
        new android.graphics.Canvas(padded).drawBitmap(bitmap, 0, (32 - bitmap.getHeight()) / 2, null);
        bitmap.recycle();
        return padded;
    }

    private void runJob(String json) {
        PrinterBackend backend = null;
        int sent = 0;
        try {
            if (json.length() > 4 * 1024 * 1024) throw new IOException("打印内容过大，请分段打印");
            JSONObject job = new JSONObject(json);
            String kind = job.getString("kind");
            int copies = job.getInt("copies");
            if (copies < 1 || copies > 20) throw new IOException("份数应为 1 到 20");
            String mode = job.optString("mode", "auto");
            if (!Arrays.asList("auto", "service", "serialwifi", "serial4g").contains(mode)) throw new IOException("打印接口无效");
            int count;
            int[] pdfPages = null;
            JSONArray ids = null;
            ImportStore.Item pdf = null;
            DocumentRenderer renderer = null;
            if ("html".equals(kind)) {
                int font = job.optInt("fontSize", 18);
                if (font < 12 || font > 30) throw new IOException("打印字号超出范围");
                renderer = new DocumentRenderer(this, renderView);
                progress("正在排版", "渲染公式、字体和图片", 0);
                renderer.prepare(job.getString("html"), font, localClient);
                count = renderer.pages();
            } else if ("pdf".equals(kind)) {
                pdf = imports.get(job.getString("id"));
                if (!"pdf".equals(pdf.kind)) throw new IOException("文件类型不匹配");
                pdfPages = parsePages(job.optString("range", ""), pdf.pages);
                count = pdfPages.length;
            } else if ("images".equals(kind)) {
                ids = job.getJSONArray("ids"); count = ids.length();
                if (count == 0 || count > 100) throw new IOException("请选择 1 到 100 张图片");
                for (int index = 0; index < count; index++) if (!"image".equals(imports.get(ids.getString(index)).kind)) throw new IOException("图片转换尚未完成");
            } else throw new IOException("任务类型无效");
            if (count * copies > 1000) throw new IOException("单次任务不能超过 1000 页，请分批打印");
            if (cancelled.get()) throw new IOException("已停止后续打印");
            backend = PrinterBackend.open(this, mode, log);
            final String backendName = backend.name();
            log.add("开始任务 " + kind + " / " + count + " 页 × " + copies + " 份 / " + backendName);
            for (int copy = 0; copy < copies; copy++) {
                for (int page = 0; page < count; page++) {
                    if (cancelled.get()) throw new IOException("已停止后续打印");
                    Bitmap bitmap;
                    if (renderer != null) bitmap = renderer.capture(page);
                    else if (pdf != null) bitmap = imports.pdfPage(pdf, pdfPages[page], Protocol.WIDTH);
                    else bitmap = imports.image(imports.get(ids.getString(page)), Protocol.WIDTH);
                    bitmap = padShortImage(bitmap);
                    final int completed = sent;
                    final int total = count * copies;
                    String title = "第 " + (copy + 1) + "/" + copies + " 份 · 第 " + (page + 1) + "/" + count + " 页";
                    try {
                        backend.print(bitmap, (message, percent) -> progress(title, backendName + " · " + message, (completed * 100 + percent) / total), cancelled);
                    } finally {
                        // A submitted service job can outlive a failed registration or timeout.
                        // Let its remote provider retain the bitmap until Binder releases it.
                        if (!backend.mayStillUseBitmap()) bitmap.recycle();
                    }
                    sent++;
                }
            }
            boolean stopped = cancelled.get();
            String message = backend instanceof PrinterBackend.SerialPrinter ? "已发送 " + sent + " 页及走纸指令，请检查实际出纸。" : "系统已回报 " + sent + " 页结束，返回码为 0，请检查实际出纸。";
            finished(!stopped, stopped ? "后续任务已停止，当前已提交页面可能继续打印。" : message);
        } catch (Exception | LinkageError | OutOfMemoryError error) {
            log.add("打印终止 / 已结束 " + sent + " 页\n" + android.util.Log.getStackTraceString(error));
            finished(false, userMessage(error) + "。已结束 " + sent + " 页。未自动重试，请核对出纸情况。");
        } finally {
            try { if (backend != null) backend.close(); }
            catch (Exception error) { log.add("关闭打印接口失败 " + error); }
            finally {
                printing.set(false);
                runOnUiThread(() -> { if (!destroyed) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); });
            }
        }
    }

    private void progress(String title, String message, int percent) {
        try { emit("progress", new JSONObject().put("title", title).put("message", message).put("percent", percent)); }
        catch (Exception error) { log.add(error.toString()); }
    }
    private void finished(boolean ok, String message) {
        try { emit("finished", new JSONObject().put("ok", ok).put("message", message)); }
        catch (Exception error) { log.add(error.toString()); }
    }
    @Override public void onBackPressed() {
        if (printing.get()) { new AlertDialog.Builder(this).setMessage("打印任务正在执行，可在进度面板停止后续打印。").setPositiveButton("继续查看", null).show(); return; }
        web.evaluateJavascript("window.goHome&&window.goHome()", result -> { if (!"true".equals(result)) finish(); });
    }
    @Override protected void onDestroy() {
        destroyed = true;
        cancelled.set(true);
        worker.shutdownNow();
        web.removeJavascriptInterface("Android");
        web.destroy(); renderView.destroy();
        super.onDestroy();
    }
}
