package io.netty.buffer;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

public class RPByteBufTest {
    @Test
    public void testIsWritable() {
        EmptyByteBuf empty = new EmptyByteBuf(RPByteBufAllocator.DEFAULT);
        assertFalse(empty.isWritable());
        assertFalse(empty.isWritable(1));
    }

    @Test
    public void ensureWritableWithEnoughSpaceShouldNotThrow() {
        ByteBuf buf = RPByteBufAllocator.DEFAULT.heapBuffer(1, 10);
        long start = System.nanoTime();
        for (int i = 0; i<1000000; i++) {
            buf = RPByteBufAllocator.DEFAULT.heapBuffer(1, 10);
        }
        System.out.print("RP time in nanoseconds = ");//806274227
        System.out.println(System.nanoTime() - start);

        start = System.nanoTime();
        for (int i = 0; i<1000000; i++) {
            buf = RPByteBufAllocator.DEFAULT.heapBuffer(10, 100);
        }
        System.out.print("RP time in nanoseconds = "); //1461830222
        System.out.println(System.nanoTime() - start);

        start = System.nanoTime();
        for (int i = 0; i<1000000; i++) {
            buf = RPByteBufAllocator.DEFAULT.heapBuffer(100, 1000);
        }
        System.out.print("RP time in nanoseconds = "); //2363127897
        System.out.println(System.nanoTime() - start);

        start = System.nanoTime();
        for (int i = 0; i<1000000; i++) {
            buf = RPByteBufAllocator.DEFAULT.heapBuffer(1000, 10000);
        }
        System.out.print("RP time in nanoseconds = "); //2894855123
        System.out.println(System.nanoTime() - start);
        buf.ensureWritable(3);
        assertThat(buf.writableBytes(), is(greaterThanOrEqualTo(3)));
        buf.release();
    }


    @Test
    public void OLDensureWritableWithEnoughSpaceShouldNotThrow() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(1, 10);
        long start = System.nanoTime();
        for (int i = 0; i<1000000; i++) {
            buf = ByteBufAllocator.DEFAULT.heapBuffer(1, 10);
        }
        System.out.print("RP time in nanoseconds = "); //669339297
        System.out.println(System.nanoTime() - start);

        start = System.nanoTime();
        for (int i = 0; i<1000000; i++) {
            buf = RPByteBufAllocator.DEFAULT.heapBuffer(10, 100);
        }
        System.out.print("RP time in nanoseconds = "); //644586411
        System.out.println(System.nanoTime() - start);

        start = System.nanoTime();
        for (int i = 0; i<1000000; i++) {
            buf = RPByteBufAllocator.DEFAULT.heapBuffer(100, 1000);
        }
        System.out.print("RP time in nanoseconds = "); //1096165806
        System.out.println(System.nanoTime() - start);

        start = System.nanoTime();
        for (int i = 0; i<1000000; i++) {
            buf = RPByteBufAllocator.DEFAULT.heapBuffer(1000, 10000);
        }
        System.out.print("RP time in nanoseconds = "); //1742984919
        System.out.println(System.nanoTime() - start);
        buf.ensureWritable(3);
        assertThat(buf.writableBytes(), is(greaterThanOrEqualTo(3)));
        buf.release();
    }
}