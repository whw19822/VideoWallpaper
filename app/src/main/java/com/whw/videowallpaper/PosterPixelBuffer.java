package com.whw.videowallpaper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Packs bitmap rows for GLES with GL_UNPACK_ALIGNMENT = 1. */
final class PosterPixelBuffer {
    private PosterPixelBuffer() {
    }

    static ByteBuffer pack(ByteBuffer source, int rowBytes, int width, int height,
                           int bytesPerPixel) {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0) {
            throw new IllegalArgumentException("Invalid pixel dimensions");
        }
        int packedRowBytes = Math.multiplyExact(width, bytesPerPixel);
        int packedSize = Math.multiplyExact(packedRowBytes, height);
        long requiredSize = (long) (height - 1) * rowBytes + packedRowBytes;
        if (rowBytes < packedRowBytes || requiredSize > source.limit()) {
            throw new IllegalArgumentException("Pixel buffer is smaller than its row layout");
        }
        ByteBuffer pixels = source.duplicate().order(ByteOrder.nativeOrder());
        if (rowBytes == packedRowBytes) {
            pixels.position(0);
            pixels.limit(packedSize);
            return pixels.slice().order(ByteOrder.nativeOrder());
        }
        ByteBuffer packed = ByteBuffer.allocateDirect(packedSize)
                .order(ByteOrder.nativeOrder());
        for (int row = 0; row < height; row++) {
            pixels.limit(row * rowBytes + packedRowBytes);
            pixels.position(row * rowBytes);
            packed.put(pixels);
        }
        packed.flip();
        return packed;
    }
}
