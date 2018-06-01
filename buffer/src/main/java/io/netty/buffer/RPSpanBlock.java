package io.netty.buffer;

import java.util.LinkedList;

public class RPSpanBlock {
    //! Free list
    int free_list;
    //! First autolinked block
    int    first_autolink;
    //! Free count
    int    free_count;
}
