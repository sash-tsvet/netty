package io.netty.buffer;

//A span can either represent a single span of memory pages with size declared by span_map_count configuration variable,
//or a set of spans in a continuous region, a super span. Any reference to the term "span" usually refers to both a single
//span or a super span. A super span can further be divided into multiple spans (or this, super spans), where the first
//(super)span is the master and subsequent (super)spans are subspans. The master span keeps track of how many subspans
//that are still alive and mapped in virtual memory, and once all subspans and master have been unmapped the entire
//superspan region is released and unmapped (on Windows for example, the entire superspan range has to be released
//in the same call to release the virtual memory range, but individual subranges can be decommitted individually
//to reduce physical memory use).

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class RPSpan<T> {
    //!	Heap ID
    AtomicInteger  heap_id;
    //!	Heap ID
    RPThreadHeap<T>  heap;
    //! Size class
    int    size_class;
    //! Flags and counters
    int    flags;
    //! Span data depending on use
    RPSpanData data;
    //! Total span counter for master spans, distance for subspans
    int    total_spans_or_distance;
    //! Number of spans
    int    span_count;
    //! Remaining span counter, for master spans
    AtomicInteger remaining_spans;
    //! Alignment offset
    int    align_offset;

    RPSpanList list;
    final T memory;


    //! Flag indicating span is the first (master) span of a split superspan
    static final int SPAN_FLAG_MASTER = 1;
    //! Flag indicating span is a secondary (sub) span of a split superspan
    static final int SPAN_FLAG_SUBSPAN = 2;
    //! Size of a span of memory pages
    private static int _memory_span_size;
    //! Memory page size
    private static int _memory_page_size;

    RPSpan(RPThreadHeap<T> heap, T memory, RPSpanList list, int _memory_page_size, int _memory_span_size) {
        this.list = list;
        this.heap = heap;
        this.memory = memory;
        this.data = new RPSpanData();
        this._memory_span_size = _memory_span_size;
        this._memory_page_size = _memory_page_size;
    }

    //! Unmap memory pages for the given number of spans (or mark as unused if no partial unmappings)
    void
    _memory_unmap_span() {
        int span_count = this.span_count;
        assert((this.flags & SPAN_FLAG_MASTER) == 0 || (this.flags & SPAN_FLAG_SUBSPAN) == 0);  // should be one of 2 flags
        assert((this.flags & SPAN_FLAG_MASTER) != 0 || (this.flags & SPAN_FLAG_SUBSPAN) != 0);

        boolean is_master = ((this.flags & SPAN_FLAG_MASTER) == 0);
        RPSpan master = is_master ? this : this.list.list.get(0);

        assert(is_master || (this.flags & SPAN_FLAG_SUBSPAN) != 0); // Is master or subspan
        assert((master.flags & SPAN_FLAG_MASTER) != 0); // Master is valid

        if (!is_master) {
            //Directly unmap subspans (unless huge pages, in which case we defer and unmap entire page range with master)
            assert(this.align_offset == 0);
            if (_memory_span_size >= _memory_page_size) {
                _memory_unmap(span_count * _memory_span_size, 0, 0);
//                _memory_statistics_sub(reserved_spans, span_count);
            }
        }
        else {
            //Special double flag to denote an unmapped master
            //It must be kept in memory since span header must be used
            this.flags |= SPAN_FLAG_MASTER | SPAN_FLAG_SUBSPAN;
        }

        if ((master.remaining_spans.getAndAdd(-span_count) - span_count) <= 0) {
            //Everything unmapped, unmap the master span with release flag to unmap the entire range of the super span
            assert(((master.flags & SPAN_FLAG_MASTER) != 0) && ((master.flags & SPAN_FLAG_SUBSPAN) != 0));
            int unmap_count = master.span_count;
            if (_memory_span_size < _memory_page_size)
                unmap_count = master.total_spans_or_distance;
//            _memory_statistics_sub(reserved_spans, unmap_count);
            _memory_unmap(unmap_count * _memory_span_size, master.align_offset, master.total_spans_or_distance * _memory_span_size);
        }
    }

//! Split a super span in two
    RPSpan
    _memory_span_split(int use_count) {
        int current_count = this.span_count;
        int distance = 0;
        assert(current_count > use_count); // We have enough spans for use
        assert(((this.flags & SPAN_FLAG_MASTER) != 0) || ((this.flags & SPAN_FLAG_SUBSPAN) != 0)); // Span flags were set up

        this.span_count = use_count;
        if ((this.flags & SPAN_FLAG_SUBSPAN) == 0)
            distance = this.total_spans_or_distance;

        //Setup remainder as a subspan
        RPSpan subspan = this.list.list.get(use_count);
        subspan.flags = SPAN_FLAG_SUBSPAN;
        subspan.total_spans_or_distance = distance + use_count;
        subspan.span_count = current_count - use_count;
        subspan.align_offset = 0;
        return subspan;
    }

    //! Unmap virtual memory
    static void
    _memory_unmap(int size, int offset, int release) {
        assert(release != 0 || (release >= size));
        assert(release != 0 || (release >= _memory_page_size));
        if (release != 0) {
            assert((release % _memory_page_size) != 0);
//            _memory_statistics_sub(&_mapped_pages, (release >> _memory_page_size_shift));
//            _memory_statistics_add(&_unmapped_total, (release >> _memory_page_size_shift));
        }
        // Java Machine should release memory automatically
    }
}
