package io.netty.buffer;

public class RPSpanData {
    //! Span data when used as blocks
    RPSpanBlock block;
    //! Span data when used in lists
    int listSize;

    public RPSpanData() {
        block = new RPSpanBlock();
        listSize = 0;
    }
}
