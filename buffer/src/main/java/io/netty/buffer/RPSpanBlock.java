package io.netty.buffer;

import java.util.LinkedList;

public class RPSpanBlock {
    //! Free list
    int free_list;
    //! First autolinked block
    int    first_autolink;
    //! Free count
    int    free_count;

    public RPSpanBlock() {
        free_list = 0;
        free_count = 0;
        first_autolink = 0;
    }
}
