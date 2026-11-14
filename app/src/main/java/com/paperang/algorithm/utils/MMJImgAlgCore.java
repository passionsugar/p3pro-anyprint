package com.paperang.algorithm.utils;

import android.graphics.Bitmap;
import com.paperang.algorithm.m.MMJImgAlgModel;

/** Calls the identical native image converter used by the sample-image print path. */
public final class MMJImgAlgCore {
    static { System.loadLibrary("mmj_img_alg_core"); }
    private static native byte[] MMJ_PrinterImgBinData(MMJImgAlgModel model, int[][] points, int count, int mode, int width);

    public static byte[] raster(Bitmap bitmap) {
        return MMJ_PrinterImgBinData(new MMJImgAlgModel(bitmap), null, 0, 0, 864);
    }
}
