package com.whw.videowallpaper;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import org.junit.Test;

public final class PosterPixelBufferTest {
    @Test
    public void oddWidthRgb565UsesExactlyItsPixelBytes() {
        ByteBuffer input = ByteBuffer.allocateDirect(439 * 2 * 1024);
        input.put(0, (byte) 7);
        input.put(input.capacity() - 1, (byte) 9);
        ByteBuffer packed = PosterPixelBuffer.pack(input, 878, 439, 1024, 2);
        assertTrue(packed.isDirect());
        assertEquals(899072, packed.remaining());
        assertEquals(7, packed.get(0));
        assertEquals(9, packed.get(packed.limit() - 1));
    }

    @Test
    public void paddedRowsExcludePaddingAndPreserveSourcePosition() {
        ByteBuffer input = ByteBuffer.allocateDirect(16);
        input.put(new byte[] {1,2,3,4,5,6,99,99,7,8,9,10,11,12,99,99});
        input.flip();
        ByteBuffer packed = PosterPixelBuffer.pack(input, 8, 3, 2, 2);
        byte[] actual = new byte[packed.remaining()];
        packed.get(actual);
        assertArrayEquals(new byte[] {1,2,3,4,5,6,7,8,9,10,11,12}, actual);
        assertEquals(0, input.position());
        assertEquals(16, input.limit());
    }

    @Test(expected = IllegalArgumentException.class)
    public void truncatedLastRowIsRejectedBeforeNativeUpload() {
        PosterPixelBuffer.pack(ByteBuffer.allocateDirect(13), 8, 3, 2, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void strideSmallerThanPixelsIsRejected() {
        PosterPixelBuffer.pack(ByteBuffer.allocateDirect(12), 4, 3, 2, 2);
    }
}
