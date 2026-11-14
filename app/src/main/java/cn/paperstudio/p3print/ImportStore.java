package cn.paperstudio.p3print;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ImportStore {
    public static final int MAX_HEIGHT = 20000;
    public static final class Item {
        public String id;
        public String name;
        public String kind;
        public String mime;
        public String text;
        public File file;
        public File thumbnail;
        public int pages = 1;
        public int width;
        public int height;
        public JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("kind", kind).put("mime", mime)
                    .put("pages", pages).put("width", width).put("height", height).put("text", text == null ? "" : text)
                    .put("url", "/files/" + id).put("thumbnail", "/thumb/" + id);
        }
    }
    private final Context context;
    private final File directory;
    private final Map<String, Item> items = new ConcurrentHashMap<>();

    public ImportStore(Context context) {
        this.context = context;
        directory = new File(context.getCacheDir(), "imports");
        directory.mkdirs();
        // Imported copies are session cache. Source files and WebView drafts have separate storage.
        File[] previous = directory.listFiles();
        if (previous != null) for (File file : previous) if (file.isFile()) file.delete();
    }

    public Item get(String id) throws IOException {
        Item item = items.get(id);
        if (item == null) throw new IOException("文件已失效，请重新选择");
        return item;
    }

    public Item add(Uri uri) throws Exception {
        if (items.size() >= 100) throw new IOException("一次最多导入 100 个文件，请重启应用后重新选择");
        Item item = new Item();
        item.id = UUID.randomUUID().toString();
        item.name = uri.getLastPathSegment() == null ? "未命名文件" : uri.getLastPathSegment();
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) item.name = cursor.getString(0);
        }
        String lower = item.name.toLowerCase(Locale.ROOT);
        item.mime = context.getContentResolver().getType(uri);
        if (item.mime == null) item.mime = "application/octet-stream";
        item.file = new File(directory, item.id);
        if (directory.getUsableSpace() < 160L * 1024 * 1024) throw new IOException("本机可用空间不足 160 MB，请释放空间后导入");
        try (InputStream source = context.getContentResolver().openInputStream(uri); FileOutputStream target = new FileOutputStream(item.file)) {
            if (source == null) throw new IOException("无法读取所选文件");
            byte[] buffer = new byte[32768];
            int count;
            long total = 0;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > 128L * 1024 * 1024) throw new IOException("单个文件不能超过 128 MB");
                target.write(buffer, 0, count);
            }
        }
        if (lower.endsWith(".pdf") || "application/pdf".equals(item.mime)) {
            item.kind = "pdf";
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(descriptor)) {
                item.pages = renderer.getPageCount();
                if (item.pages < 1) throw new IOException("PDF 没有可打印页面");
                try (PdfRenderer.Page page = renderer.openPage(0)) {
                    item.width = page.getWidth(); item.height = page.getHeight();
                }
            }
            Bitmap first = pdfPage(item, 0, 256);
            saveThumbnail(item, first);
            first.recycle();
        } else if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt") || item.mime.startsWith("text/")) {
            item.kind = lower.endsWith(".md") || lower.endsWith(".markdown") ? "md" : "txt";
            if (item.file.length() > 2L * 1024 * 1024) throw new IOException("文本文件不能超过 2 MB，请分段导入");
            byte[] bytes = readBytes(item.file);
            item.text = decodeText(bytes);
        } else if (lower.endsWith(".tif") || lower.endsWith(".tiff") || lower.endsWith(".svg")) {
            item.kind = "special";
            item.mime = lower.endsWith(".svg") ? "image/svg+xml" : "image/tiff";
        } else {
            item.kind = "image";
            Bitmap bitmap = image(item, 256);
            saveThumbnail(item, bitmap);
            bitmap.recycle();
        }
        items.put(item.id, item);
        return item;
    }

    public void commitRaster(String id, String dataUrl) throws Exception {
        Item item = get(id);
        if (!"special".equals(item.kind)) throw new IOException("文件不需要转换");
        if (!dataUrl.startsWith("data:image/png;base64,") || dataUrl.length() > 48 * 1024 * 1024) throw new IOException("图片转换结果无效或过大");
        File converted = new File(directory, item.id + ".png");
        byte[] bytes = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
        try (FileOutputStream stream = new FileOutputStream(converted)) { stream.write(bytes); }
        item.file = converted;
        item.mime = "image/png";
        Bitmap bitmap = image(item, 256);
        saveThumbnail(item, bitmap);
        bitmap.recycle();
        item.kind = "image";
    }

    private void saveThumbnail(Item item, Bitmap bitmap) throws IOException {
        item.thumbnail = new File(directory, item.id + "-thumb.png");
        try (FileOutputStream stream = new FileOutputStream(item.thumbnail)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }

    public Bitmap image(Item item, int targetWidth) throws Exception {
        Bitmap bitmap;
        if (Build.VERSION.SDK_INT >= 28) {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(item.file), (decoder, info, source) -> {
                item.width = info.getSize().getWidth();
                item.height = info.getSize().getHeight();
                int height = scaledHeight(item.width, item.height, targetWidth);
                decoder.setTargetSize(targetWidth, height);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(true);
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
            });
        } else {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(item.file.getAbsolutePath(), options);
            item.width = options.outWidth; item.height = options.outHeight;
            scaledHeight(item.width, item.height, targetWidth);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = true;
            int sample = 1;
            while (options.outWidth / (sample * 2) >= targetWidth && options.outHeight / (sample * 2) >= 1) sample *= 2;
            options.inSampleSize = sample;
            bitmap = BitmapFactory.decodeFile(item.file.getAbsolutePath(), options);
            if (bitmap == null) throw new IOException("系统无法解码图片，请转换为 PNG/JPEG。HEIF/AVIF 依赖设备系统版本");
            try {
                ExifInterface exif = new ExifInterface(item.file.getAbsolutePath());
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                Matrix transform = new Matrix();
                switch (orientation) {
                    case 2: transform.setScale(-1, 1); break;
                    case 3: transform.setRotate(180); break;
                    case 4: transform.setScale(1, -1); break;
                    case 5: transform.setRotate(90); transform.postScale(-1, 1); break;
                    case 6: transform.setRotate(90); break;
                    case 7: transform.setRotate(270); transform.postScale(-1, 1); break;
                    case 8: transform.setRotate(270); break;
                    default: break;
                }
                if (!transform.isIdentity()) {
                    Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
                    if (rotated != bitmap) bitmap.recycle();
                    bitmap = rotated;
                }
            } catch (IOException ignored) { /* Formats without EXIF retain their decoded orientation. */ }
        }
        int height = scaledHeight(bitmap.getWidth(), bitmap.getHeight(), targetWidth);
        if (bitmap.getWidth() == targetWidth && bitmap.getHeight() == height && bitmap.isMutable() && bitmap.getConfig() == Bitmap.Config.ARGB_8888) {
            new Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER);
            bitmap.setHasAlpha(false);
            return bitmap;
        }
        Bitmap white = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(white);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, targetWidth, height), paint);
        bitmap.recycle();
        return white;
    }

    private static int scaledHeight(int width, int height, int targetWidth) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("图片格式不支持或文件已损坏");
        long scaled = Math.max(1L, Math.round((double) height * targetWidth / width));
        if (scaled > MAX_HEIGHT) throw new IllegalArgumentException("图片适应宽度后过长，请分割为高度不超过 " + MAX_HEIGHT + " 点的图片");
        return (int) scaled;
    }

    public Bitmap pdfPage(Item item, int index, int width) throws Exception {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor);
             PdfRenderer.Page page = renderer.openPage(index)) {
            int height = scaledHeight(page.getWidth(), page.getHeight(), width);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            return bitmap;
        }
    }

    public static byte[] readBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    static String decodeText(byte[] bytes) throws CharacterCodingException {
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\uFEFF", "");
        } catch (CharacterCodingException error) {
            return java.nio.charset.Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
}
