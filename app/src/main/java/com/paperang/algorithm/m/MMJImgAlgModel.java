package com.paperang.algorithm.m;

import android.graphics.Bitmap;

/** Field and method names are part of the vendor JNI contract. */
public final class MMJImgAlgModel {
    public int width;
    public int height;
    public int sourceType = 0;
    public int resultCode = -1;
    private Bitmap originBitmap;

    public MMJImgAlgModel(Bitmap bitmap) {
        originBitmap = bitmap;
        width = bitmap.getWidth();
        height = bitmap.getHeight();
    }

    public Bitmap getOriginBitmap() { return originBitmap; }
}
