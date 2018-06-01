package io.netty.buffer;

public class RPSizeClass {
    //! Size of blocks in this class
    int size;
    //! Number of blocks in each chunk
    int block_count;
    //! Class index this class is merged with
    int class_idx;
}
