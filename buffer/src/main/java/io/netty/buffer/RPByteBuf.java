package io.netty.buffer;

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

abstract class RPByteBuf<T> extends AbstractReferenceCountedByteBuf {

    private final Recycler.Handle<RPByteBuf<T>> recyclerHandle;

    protected RPSpan<T> span;
    protected long handle;
    protected T memory;
    protected int offset;
    protected int length;
    int maxLength;
//    PoolThreadCache cache;
    private ByteBuffer tmpNioBuf;
    private ByteBufAllocator allocator;

    protected RPByteBuf(Handle<? extends RPByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<RPByteBuf<T>>) recyclerHandle;
    }

//    void init(PoolChunk<T> chunk, long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
//        init0(chunk, handle, offset, length, maxLength, cache);
//    }
//
//    void initUnpooled(PoolChunk<T> chunk, int length) {
//        init0(chunk, 0, chunk.offset, length, length, null);
//    }

    private void init0(RPSpan<T> span, long handle, int offset, int length, int maxLength){//}, PoolThreadCache cache) {
        assert handle >= 0;
        assert span != null;

        this.span = span;
        memory = span.memory;
//        allocator = span.arena.parent;
//        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
        tmpNioBuf = null;
    }

    final void reuse(int maxCapacity) {
        maxCapacity(maxCapacity);
        setRefCnt(1);
        setIndex0(0, 0);
        discardMarks();
    }

    @Override
    public final int capacity() {
        return length;
    }

    @Override
    public final ByteBuf capacity(int newCapacity) {
        checkNewCapacity(newCapacity);

        // If the request capacity does not require reallocation, just update the length of the memory.
//        if (chunk.unpooled) {
//            if (newCapacity == length) {
//                return this;
//            }
//        } else {
//            if (newCapacity > length) {
//                if (newCapacity <= maxLength) {
//                    length = newCapacity;
//                    return this;
//                }
//            } else if (newCapacity < length) {
//                if (newCapacity > maxLength >>> 1) {
//                    if (maxLength <= 512) {
//                        if (newCapacity > maxLength - 16) {
//                            length = newCapacity;
//                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
//                            return this;
//                        }
//                    } else { // > 512 (i.e. >= 1024)
//                        length = newCapacity;
//                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
//                        return this;
//                    }
//                }
//            } else {
//                return this;
//            }
//        }
//
//        // Reallocation required.
//        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    @Override
    protected final void deallocate() {
        if (handle >= 0) {
            final long handle = this.handle;
            this.handle = -1;
            memory = null;
            tmpNioBuf = null;
//            chunk.arena.free(chunk, handle, maxLength, cache);
            span = null;
            recycle();
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this);
    }

    protected final int idx(int index) {
        return offset + index;
    }
}
