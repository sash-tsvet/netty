package io.netty.buffer;

public class RPByteBufAllocatorMetric implements ByteBufAllocatorMetric {
    private final RPByteBufAllocator allocator;

    RPByteBufAllocatorMetric(RPByteBufAllocator allocator) {
        this.allocator = allocator;
    }

    @Override
    public long usedHeapMemory() {
        return 0; //allocator.usedHeapMemory();
    }

    @Override
    public long usedDirectMemory() {
        return 0; //allocator.usedDirectMemory();
    }
}
