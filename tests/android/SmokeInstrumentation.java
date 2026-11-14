package cn.paperstudio.p3print;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.webkit.WebView;
import com.paperang.algorithm.utils.MMJImgAlgCore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SmokeInstrumentation extends Instrumentation {
    private final JSONObject results = new JSONObject();
    private final JSONArray checks = new JSONArray();
    private File output;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private void check(boolean value, String message) throws Exception {
        if (!value) throw new AssertionError(message);
        checks.put(message);
    }

    @Override public void onStart() {
        Bundle bundle = new Bundle();
        try {
            Context context = getTargetContext();
            results.put("appVersion", AppLog.VERSION);
            output = context.getExternalFilesDir(null);
            if (output == null) output = context.getFilesDir();
            output.mkdirs();
            nativeRaster();
            imports(context);
            binder();
            protectedBroadcasts(context);
            uiAndRenderer();
            results.put("result", "PASS").put("checks", checks).put("android", android.os.Build.VERSION.RELEASE);
            bundle.putString("result", results.toString());
            saveResults();
            finish(Activity.RESULT_OK, bundle);
        } catch (Throwable error) {
            try { results.put("result", "FAIL").put("checks", checks).put("error", error.toString()); saveResults(); } catch (Exception ignored) {}
            bundle.putString("error", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, bundle);
        }
    }

    private void saveResults() throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(output, "android-results.json"))) { stream.write(results.toString(2).getBytes(StandardCharsets.UTF_8)); }
    }

    private void nativeRaster() throws Exception {
        Bitmap white = Bitmap.createBitmap(864, 32, Bitmap.Config.ARGB_8888);
        white.eraseColor(Color.WHITE);
        byte[] blank = MMJImgAlgCore.raster(white);
        white.eraseColor(Color.BLACK);
        byte[] black = MMJImgAlgCore.raster(white);
        white.recycle();
        check(blank != null && black != null, "Vendor JNI loads and returns raster data");
        results.put("whiteBytes", blank.length).put("blackBytes", black.length);
        check(blank.length == 3456 && black.length == 3456, "864 × 32 native raster is 3456 bytes");
        check(Arrays.equals(blank, new byte[3456]), "White pixels encode as zero bits");
        byte[] solid = new byte[3456]; Arrays.fill(solid, (byte) 255);
        check(Arrays.equals(black, solid), "Black pixels encode as one bits");
        Bitmap narrow = Bitmap.createBitmap(432, 16, Bitmap.Config.ARGB_8888);
        narrow.eraseColor(Color.BLACK);
        byte[] scaled = MMJImgAlgCore.raster(narrow);
        narrow.recycle();
        check(scaled.length == 3456, "Vendor converter proportionally scales 432 × 16 to 864 × 32");
        Bitmap bitOrder = Bitmap.createBitmap(864, 64, Bitmap.Config.ARGB_8888);
        bitOrder.eraseColor(Color.WHITE);
        Paint marker = new Paint(); marker.setColor(Color.BLACK);
        new Canvas(bitOrder).drawRect(0, 0, 4, 64, marker);
        byte[] packed = MMJImgAlgCore.raster(bitOrder);
        bitOrder.recycle();
        results.put("bitOrderFirstByte", packed[32 * 108] & 255).put("bitOrderBytes", packed.length);
        check(packed.length == 6912 && (packed[32 * 108] & 255) == 240, "Raster is row-major and most-significant-bit first");
        for (int attempt = 0; attempt < 16; attempt++) {
            Bitmap shortImage = Bitmap.createBitmap(864, 1, Bitmap.Config.ARGB_8888);
            shortImage.eraseColor(Color.WHITE); shortImage.setPixel(0, 0, Color.BLACK);
            Bitmap padded = MainActivity.padShortImage(shortImage);
            byte[] shortRaster = MMJImgAlgCore.raster(padded);
            padded.recycle();
            if (shortRaster.length != 3456 || (shortRaster[15 * 108] & 255) != 128) throw new AssertionError("Short image padding failed, attempt " + attempt);
        }
        check(true, "Single-row images remain stable across 16 vendor-converter calls after white padding");
    }

    private File file(Context context, String name, byte[] bytes) throws Exception {
        File file = new File(context.getCacheDir(), name);
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(bytes); }
        return file;
    }

    private void imports(Context context) throws Exception {
        ImportStore store = new ImportStore(context);
        File png = new File(context.getCacheDir(), "qa-alpha.png");
        Bitmap alpha = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
        alpha.eraseColor(Color.TRANSPARENT); alpha.setPixel(100, 50, Color.BLACK);
        try (FileOutputStream stream = new FileOutputStream(png)) { alpha.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        alpha.recycle();
        ImportStore.Item image = store.add(Uri.fromFile(png));
        Bitmap decoded = store.image(image, 864);
        check(decoded.getWidth() == 864 && decoded.getHeight() == 432, "Image import uses proportional fit-to-width");
        check(decoded.getPixel(0, 0) == Color.WHITE, "Transparent image background becomes white");
        decoded.recycle();
        Bitmap formatFixture = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        formatFixture.eraseColor(Color.BLACK);
        for (String format : new String[]{"jpeg", "webp"}) {
            File fixture = new File(context.getCacheDir(), "qa-format." + format);
            try (FileOutputStream stream = new FileOutputStream(fixture)) { formatFixture.compress("jpeg".equals(format) ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.WEBP, 90, stream); }
            ImportStore.Item imported = store.add(Uri.fromFile(fixture));
            Bitmap result = store.image(imported, 864);
            check(result.getWidth() == 864 && result.getHeight() == 691, format + " imports and scales correctly");
            result.recycle();
        }
        formatFixture.recycle();
        byte[] gif = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT);
        ImportStore.Item gifItem = store.add(Uri.fromFile(file(context, "qa-format.gif", gif)));
        Bitmap gifBitmap = store.image(gifItem, 864);
        check(gifBitmap.getWidth() == 864 && gifBitmap.getHeight() == 864, "GIF first frame imports and scales correctly");
        gifBitmap.recycle();
        java.nio.ByteBuffer bmp = java.nio.ByteBuffer.allocate(58).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bmp.put((byte) 'B').put((byte) 'M').putInt(58).putInt(0).putInt(54).putInt(40).putInt(1).putInt(1).putShort((short) 1).putShort((short) 24).putInt(0).putInt(4).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
        ImportStore.Item bmpItem = store.add(Uri.fromFile(file(context, "qa-format.bmp", bmp.array())));
        Bitmap bmpBitmap = store.image(bmpItem, 864);
        check(bmpBitmap.getWidth() == 864 && (bmpBitmap.getPixel(0, 0) & 0xffffff) == 0, "24-bit BMP imports and scales correctly");
        bmpBitmap.recycle();
        ImportStore.Item gb = store.add(Uri.fromFile(file(context, "qa-gb.txt", "中文文本".getBytes(Charset.forName("GB18030")))));
        check("中文文本".equals(gb.text), "GB18030 TXT decodes correctly");
        ImportStore.Item utf = store.add(Uri.fromFile(file(context, "qa-utf.md", "\ufeff# 公式 $x^2$".getBytes(StandardCharsets.UTF_8))));
        check(utf.text.startsWith("# 公式") && "md".equals(utf.kind), "UTF-8 BOM Markdown imports correctly");
        File pdf = new File(context.getCacheDir(), "qa-pages.pdf");
        PdfDocument document = new PdfDocument();
        for (int index = 0; index < 2; index++) {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(200, 300, index + 1).create());
            Paint paint = new Paint(); paint.setColor(Color.BLACK); paint.setTextSize(25);
            page.getCanvas().drawText("PDF PAGE " + (index + 1), 10, 50, paint); document.finishPage(page);
        }
        try (FileOutputStream stream = new FileOutputStream(pdf)) { document.writeTo(stream); }
        document.close();
        ImportStore.Item pdfItem = store.add(Uri.fromFile(pdf));
        check(pdfItem.pages == 2 && "pdf".equals(pdfItem.kind), "PDF import counts both pages");
        Bitmap page = store.pdfPage(pdfItem, 1, 864);
        check(page.getWidth() == 864 && page.getHeight() == 1296, "PDF page raster has correct fit-to-width aspect ratio");
        try (FileOutputStream stream = new FileOutputStream(new File(output, "pdf-page.png"))) { page.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        page.recycle();
        check(Arrays.equals(MainActivity.parsePages("2,1-2", 2), new int[]{1, 0}), "PDF range parsing preserves selected order and removes duplicates");
        boolean rejected = false;
        try { MainActivity.parsePages("0-3", 2); } catch (Exception expected) { rejected = true; }
        check(rejected, "Invalid PDF ranges are rejected before printing");
    }

    private static final class FakeService extends Binder {
        IBinder provider;
        int density;
        int copies;
        int bitmapWidth;
        int unregisters;
        int state;
        FakeService() { attachInterface(null, "com.paperang.printinterface.IMBPrintService"); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString("com.paperang.printinterface.IMBPrintService"); return true; }
            data.enforceInterface("com.paperang.printinterface.IMBPrintService");
            if (code == 12) { reply.writeNoException(); reply.writeInt(864); return true; }
            if (code == 4) {
                if (state == Integer.MIN_VALUE) reply.writeException(new SecurityException("Status query rejected"));
                else { reply.writeNoException(); reply.writeInt(state); }
                return true;
            }
            if (code == 1) {
                if (data.readInt() != 1) throw new RemoteException("Missing bundle marker");
                Bundle bundle = Bundle.CREATOR.createFromParcel(data);
                provider = bundle.getBinder("bitmap"); density = data.readInt(); copies = data.readInt();
                Parcel request = Parcel.obtain(); Parcel response = Parcel.obtain();
                try {
                    request.writeInterfaceToken("com.paperang.printinterface.IRemoteGetBitmap"); provider.transact(1, request, response, 0); response.readException();
                    if (response.readInt() != 1) throw new RemoteException("Missing bitmap marker");
                    Bitmap bitmap = Bitmap.CREATOR.createFromParcel(response); bitmapWidth = bitmap.getWidth(); bitmap.recycle();
                } finally { request.recycle(); response.recycle(); }
                reply.writeNoException(); reply.writeLong(37); return true;
            }
            if (code == 5) {
                if (data.readLong() != 37) throw new RemoteException("Incorrect job id");
                IBinder callback = data.readStrongBinder();
                for (int event = 1; event <= 4; event++) {
                    Parcel request = Parcel.obtain(); Parcel response = Parcel.obtain();
                    try {
                        request.writeInterfaceToken("com.paperang.printinterface.IMBPrintCallBack");
                        if (event != 1) request.writeInt(event == 2 ? 50 : 0);
                        callback.transact(event, request, response, 0); response.readException();
                    } finally { request.recycle(); response.recycle(); }
                }
                reply.writeNoException(); return true;
            }
            if (code == 6) { data.readStrongBinder(); unregisters++; reply.writeNoException(); return true; }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private void binder() throws Exception {
        FakeService mock = new FakeService();
        PrinterBackend.SystemPrinter backend = new PrinterBackend.SystemPrinter(mock, new AppLog());
        Bitmap bitmap = Bitmap.createBitmap(864, 100, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.WHITE);
        backend.print(bitmap, (message, percent) -> {}, new AtomicBoolean());
        check(mock.density == 0 && mock.copies == 1 && mock.bitmapWidth == 864, "System Binder print request and remote bitmap provider match extracted ABI");
        check(mock.unregisters == 1, "System completion callbacks release the registered callback");
        mock.state = 16;
        boolean rejected = false;
        try { backend.print(bitmap, (message, percent) -> {}, new AtomicBoolean()); } catch (Exception expected) { rejected = true; }
        check(rejected, "System out-of-paper status prevents submission");
        mock.state = Integer.MIN_VALUE;
        mock.bitmapWidth = 0;
        backend.print(bitmap, (message, percent) -> {}, new AtomicBoolean());
        check(mock.bitmapWidth == 864, "Status-query failure still permits the original system bitmap print request");
        bitmap.recycle(); backend.close();
    }

    private Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private void protectedBroadcasts(Context context) throws Exception {
        int[] attempts = {0};
        android.content.ContextWrapper denied = new android.content.ContextWrapper(context) {
            @Override public void sendBroadcast(Intent intent) {
                attempts[0]++;
                throw new SecurityException("Permission Denial: not allowed to send broadcast " + intent.getAction());
            }
        };
        AppLog log = new AppLog();
        boolean lease = PrinterBackend.requestSerialLease(denied, log);
        check(!lease && attempts[0] == 1, "Protected factory-mode broadcast denial is recorded and returns control to serial setup");
        PrinterBackend.releaseSerialLease(denied, lease, log);
        check(attempts[0] == 1, "Cleanup sends a factory-mode exit only for an accepted entry request");
        int[] accepted = {0};
        android.content.ContextWrapper exitDenied = new android.content.ContextWrapper(context) {
            @Override public void sendBroadcast(Intent intent) {
                accepted[0]++;
                if (intent.getAction().endsWith("OUT")) throw new SecurityException("Protected exit broadcast");
            }
        };
        PrinterBackend.releaseSerialLease(exitDenied, PrinterBackend.requestSerialLease(exitDenied, log), log);
        check(accepted[0] == 2 && log.text().contains("系统拒绝串口归还广播"), "A denied factory-mode exit is logged without failing cleanup");
        check(Protocol.statusError(16).equals("纸盖未合上") && Protocol.statusError(1).equals("缺纸"), "Captured P3 Pro cover and paper status values map to actionable errors");
    }

    private String js(WebView view, String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        runOnMainSync(() -> view.evaluateJavascript(script, value -> { result.set(value); latch.countDown(); }));
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("JS response timed out");
        return result.get();
    }

    private void uiAndRenderer() throws Exception {
        Intent intent = new Intent(getTargetContext(), MainActivity.class); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) startActivitySync(intent);
        WebView view = (WebView) field(activity, "web");
        for (int count = 0; count < 120; count++) { if ("true".equals(js(view, "!!window.PrintApp"))) break; Thread.sleep(250); }
        check("true".equals(js(view, "!!window.PrintApp")), "Bundled offline UI runs in Android 9 WebView");
        String script = "PrintApp.quill.setText('$\\\\LaTeX$\\n$$\\\\LaTeX$$\\n\\\\[ \\\\LaTeX \\\\]\\n\\\\( \\\\LaTeX \\\\)\\n\\\\[\\n\\\\LaTeX\\n\\\\]\\n');PrintApp.showScreen('text');PrintApp.showTextPreview();document.querySelectorAll('#textPreview .katex').length";
        check("5".equals(js(view, script)), "All five math delimiter forms render in the APK WebView");
        check("true".equals(js(view, "document.documentElement.scrollWidth<=innerWidth")), "APK UI fits the landscape viewport");
        ImportStore activityStore = (ImportStore) field(activity, "imports");
        File svgFile = file(getTargetContext(), "qa-native.svg", "<svg xmlns='http://www.w3.org/2000/svg' width='80' height='40'><rect width='80' height='40' fill='black'/></svg>".getBytes(StandardCharsets.UTF_8));
        ImportStore.Item svg = activityStore.add(Uri.fromFile(svgFile));
        String payload = new JSONObject().put("target", "images").put("items", new JSONArray().put(svg.json())).toString();
        js(view, "onNativeEvent('imported'," + payload + ")");
        for (int count = 0; count < 80 && !"image".equals(svg.kind); count++) Thread.sleep(100);
        check("image".equals(svg.kind) && svg.width == 864 && svg.height == 432, "SVG converts through the APK WebView and commits a native printable PNG");
        WebView rendering = (WebView) field(activity, "renderView");
        MainActivity.LocalClient client = (MainActivity.LocalClient) field(activity, "localClient");
        DocumentRenderer renderer = new DocumentRenderer(activity, rendering);
        String html = "<h1>打印测试</h1><p class='ql-align-right'>右对齐</p><p>公式 $\\frac{1}{2}$</p><p>\\[\\int_0^1x^2dx=\\frac13\\]</p>";
        renderer.prepare(html, 18, client);
        Bitmap bitmap = renderer.capture(0);
        int darkPixels = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 2) for (int x = 0; x < bitmap.getWidth(); x += 2) if ((bitmap.getPixel(x, y) & 0xffffff) < 0x808080) darkPixels++;
        results.put("capturedWidth", bitmap.getWidth()).put("capturedHeight", bitmap.getHeight()).put("darkPixels", darkPixels);
        check(bitmap.getWidth() == 864 && darkPixels > 200, "Native offscreen WebView capture contains rendered text and formulas at 864 points");
        try (FileOutputStream stream = new FileOutputStream(new File(output, "native-math-capture.png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
        fallbackFilePicker(activity, view);
        activity.new Bridge().copyDiagnostics();
        waitForIdleSync();
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        check(clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemAt(0).getText().toString().contains("随心打印 1.0.1"), "Diagnostic copy action includes the running app version and device details");
    }

    private void fallbackFilePicker(MainActivity activity, WebView view) throws Exception {
        try (android.os.ParcelFileDescriptor command = getUiAutomation().executeShellCommand("pm grant cn.paperstudio.p3print android.permission.READ_EXTERNAL_STORAGE");
             java.io.FileInputStream response = new java.io.FileInputStream(command.getFileDescriptor())) {
            byte[] buffer = new byte[256]; while (response.read(buffer) >= 0) {}
        }
        File fixture = new File(output, "qa-local-picker.txt");
        try (FileOutputStream stream = new FileOutputStream(fixture)) { stream.write("本机文件选择测试".getBytes(StandardCharsets.UTF_8)); }
        java.lang.reflect.Method browse = MainActivity.class.getDeclaredMethod("showLocalDirectory", File.class);
        browse.setAccessible(true);
        runOnMainSync(() -> {
            try { browse.invoke(activity, output); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        Thread.sleep(350);
        android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText("qa-local-picker.txt");
        check(!matches.isEmpty(), "Built-in fallback browser displays files from shared storage");
        android.view.accessibility.AccessibilityNodeInfo candidate = matches.get(0);
        while (candidate != null && !candidate.isClickable()) candidate = candidate.getParent();
        check(candidate != null && candidate.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK), "Built-in fallback browser accepts a selected TXT file");
        for (int count = 0; count < 80; count++) {
            if ("true".equals(js(view, "!!(PrintApp.state.document&&PrintApp.state.document.name==='qa-local-picker.txt')"))) break;
            Thread.sleep(100);
        }
        check("true".equals(js(view, "!!(PrintApp.state.document&&PrintApp.state.document.name==='qa-local-picker.txt')")), "Selected local TXT reaches the document preview through the native import pipeline");
    }
}
