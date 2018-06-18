package io.netty.buffer;

import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.buffer.RPSpan.SPAN_FLAG_SUBSPAN;

public class RPThreadHeap<T> {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    //! Number of small block size classes
    private static final int SMALL_CLASS_COUNT = 63;
    //! Number of medium block size classes
    private static final int MEDIUM_CLASS_COUNT = 63;
    //! Total number of small + medium size classes
    private static final int SIZE_CLASS_COUNT  = (SMALL_CLASS_COUNT + MEDIUM_CLASS_COUNT);
    //! Number of large block size classes
    private static final int LARGE_CLASS_COUNT = 32;

    //! Size of a span of memory pages
    private static final int _memory_medium_size_limit = 4096;
    private static final int _memory_page_size = 4096;
    private static final int LARGE_SIZE_LIMIT = 32*4096;
    private static final int SMALL_SIZE_LIMIT = 32*63;
    private static final int SMALL_GRANULARITY = 32;
    private static final int SMALL_GRANULARITY_SHIFT = 5;
    private static final int MEDIUM_GRANULARITY = 512;
    private static final int MEDIUM_GRANULARITY_SHIFT = 9;
    private static final int _memory_page_size_shift = 12;
    private static final int _memory_span_size_shift = 12;
    private static final int _memory_span_size = 4096;
    private static final int _memory_span_map_count = 32;
    private static final int _memory_span_mask = ~(_memory_span_size - 1);

    //! Heap ID
    AtomicInteger id;
    //! Free count for each size class active span
    RPSpanBlock[] active_block;//[SIZE_CLASS_COUNT];
    //! Active span for each size class
    RPSpan[] active_span;//[SIZE_CLASS_COUNT];
    //! List of semi-used spans with free blocks for each size class (double linked list)
    RPSpanList[]      size_cache;//[SIZE_CLASS_COUNT];
    //! List of free spans (single linked list)
    RPSpanList[]      span_cache;//[LARGE_CLASS_COUNT];
    //! Mapped but unused spans
    RPSpan      span_reserve;
    //! Master span for mapped but unused spans
    RPSpan      span_reserve_master;
    //! Number of mapped but unused spans
    long       spans_reserved;
    //! Deferred deallocation
    ArrayList<RPSpan> defer_deallocate;
    //! Next heap in id list
    RPThreadHeap      next_heap;
    //! Next heap in orphan list
    RPThreadHeap      next_orphan;
    //! Memory pages alignment offset
    long       align_offset;
//    //! Number of bytes transitioned thread -> global
//    long       thread_to_global;
//    //! Number of bytes transitioned global -> thread
//    long       global_to_thread;
    //! Global size classes
    RPSizeClass[] _memory_size_class;//[SIZE_CLASS_COUNT]
    final RPByteBufAllocator parent;

    RPSpanList fullSpanList;

    public RPThreadHeap (RPByteBufAllocator parent) {
        _memory_size_class = new RPSizeClass[SIZE_CLASS_COUNT];
        for (int i = 0; i < SIZE_CLASS_COUNT; i++) {
            RPSizeClass someObject = new RPSizeClass();
            // set properties
            _memory_size_class[i] = someObject;
        }
        for (int iclass = 0; iclass < SMALL_CLASS_COUNT; ++iclass) {
            int size = (iclass + 1) * SMALL_GRANULARITY;
            _memory_size_class[iclass].size = size;
            _memory_adjust_size_class(iclass);
        }

        for (int iclass = 0; iclass < MEDIUM_CLASS_COUNT; ++iclass) {
            int size = SMALL_SIZE_LIMIT + ((iclass + 1) * MEDIUM_GRANULARITY);
            if (size > _memory_medium_size_limit)
                size = _memory_medium_size_limit;
            _memory_size_class[SMALL_CLASS_COUNT + iclass].size = size;
            _memory_adjust_size_class(SMALL_CLASS_COUNT + iclass);
        }

        active_block = new RPSpanBlock[SIZE_CLASS_COUNT];
        for (int i = 0; i < SIZE_CLASS_COUNT; i++) {
            RPSpanBlock someObject = new RPSpanBlock();
            // set properties
            active_block[i] = someObject;
        }
        //this part of code is working, but it is not used in tests
//        size_cache = new RPSpanList[SIZE_CLASS_COUNT];

        span_cache = new RPSpanList[LARGE_CLASS_COUNT];
        active_span = new RPSpan[SIZE_CLASS_COUNT];
        this.parent = parent;
        this.fullSpanList = new RPSpanList(0, 100, 200,this);
    }


    void
    _memory_adjust_size_class(int iclass) {
        int block_size = _memory_size_class[iclass].size;
        int block_count = (_memory_span_size) / block_size;

        _memory_size_class[iclass].block_count = block_count;
        _memory_size_class[iclass].class_idx = iclass;

        //Check if previous size classes can be merged
        int prevclass = iclass;
        while (prevclass > 0) {
            --prevclass;
            //A class can be merged if number of pages and number of blocks are equal
            if (_memory_size_class[prevclass].block_count == _memory_size_class[iclass].block_count) {
                _memory_size_class[prevclass] = _memory_size_class[iclass];
            }
            else {
                break;
            }
        }
    }

    //! Map in memory pages for the given number of spans (or use previously reserved pages)
    RPSpan
    _memory_map_spans(int span_count) {
        if (span_count <= this.spans_reserved) {
            RPSpan span = this.span_reserve;
            this.span_reserve =  this.fullSpanList.list.get(span_count) ;//(RPSpan) this.span_reserve.list.list.get(this.span_reserve.list.list.indexOf(span) + span_count);
            this.spans_reserved -= span_count;
            if (span == this.span_reserve_master) {
                assert((span.flags & RPSpan.SPAN_FLAG_MASTER) != 0);
            }
            else {
                //Declare the span to be a subspan with given distance from master span
                int distance = this.fullSpanList.list.indexOf(span) - this.fullSpanList.list.indexOf(this.span_reserve_master);
                span.flags = SPAN_FLAG_SUBSPAN;
                span.total_spans_or_distance = distance;
                span.align_offset = 0;
            }
            span.span_count = span_count;
            return span;
        }

        //If we already have some, but not enough, reserved spans, release those to heap cache and map a new
        //full set of spans. Otherwise we would waste memory if page size > span size (huge pages)
        int request_spans = (span_count > _memory_span_map_count) ? span_count : _memory_span_map_count;
//        if ((_memory_page_size > _memory_span_size) && ((request_spans * _memory_span_size) % _memory_page_size) != 0)
//            request_spans += _memory_span_map_count - (request_spans % _memory_span_map_count);
        int align_offset = 0;
        RPSpanList spanList = new RPSpanList(request_spans, 100, 200, this);//!!!!!!!!!!!!
        spanList.list.getFirst().align_offset = align_offset;
        spanList.list.getFirst().total_spans_or_distance = request_spans;
        spanList.list.getFirst().span_count = span_count;
        spanList.list.getFirst().flags = RPSpan.SPAN_FLAG_MASTER;
        spanList.list.getFirst().remaining_spans = new AtomicInteger(request_spans);
        fullSpanList.list.addAll(spanList.list);
//        _memory_statistics_add(&_reserved_spans, request_spans);
        if (request_spans > span_count) {
            if (this.spans_reserved != 0) {
                RPSpan prev_span = this.span_reserve;
                if (prev_span == this.span_reserve_master) {
                    assert((prev_span.flags & RPSpan.SPAN_FLAG_MASTER) != 0);
                }
                else {
                    int distance = this.fullSpanList.list.indexOf(spanList.list.getFirst()) - this.fullSpanList.list.indexOf(this.span_reserve_master);
                    prev_span.flags = SPAN_FLAG_SUBSPAN;
                    prev_span.total_spans_or_distance = distance;
                    prev_span.align_offset = 0;
                }
                prev_span.span_count = (int)this.spans_reserved;
                prev_span.heap_id = this.id;
                _memory_heap_cache_insert(prev_span);
            }
            this.span_reserve_master = spanList.list.getFirst();
            this.span_reserve = this.fullSpanList.list.get(span_count);//(RPSpan) this.span_reserve.list.get(this.span_reserve.list.indexOf(spanList.list.getFirst()) + span_count);
            this.spans_reserved = request_spans - span_count;
        }
        return spanList.list.getFirst();
    }


    //! Insert a single span into thread heap cache, releasing to global cache if overflow
    void
    _memory_heap_cache_insert(RPSpan span) {
        int span_count = span.span_count;
        int idx = span_count - 1;
        this.span_cache[idx]._memory_span_list_push(span);
    }

//! Extract the given number of spans from the different cache levels
    RPSpan
    _memory_heap_cache_extract(int span_count) {
        int idx = span_count - 1;
        //Step 1: check thread cache
        if (this.span_cache[idx] != null)
            return this.span_cache[idx]. _memory_span_list_pop();
        //Step 2: Check reserved spans
        if (this.spans_reserved >= span_count)
            return _memory_map_spans(span_count);
        //Step 3: Check larger super spans and split if we find one
        RPSpan span = null;
        for (++idx; idx < LARGE_CLASS_COUNT; ++idx) {
            if (this.span_cache[idx] != null) {
                span = this.span_cache[idx]._memory_span_list_pop();
                break;
            }
        }
        if (span != null) {
            //Mark the span as owned by this heap before splitting
            int got_count = span.span_count;
            assert(got_count > span_count);
            span.heap_id = this.id;
//            atomic_thread_fence_release();

            //Split the span and store as reserved if no previously reserved spans, or in thread cache otherwise
            RPSpan subspan = span._memory_span_split(span_count);
            assert((span.span_count + subspan.span_count) == got_count);
            assert(span.span_count == span_count);
            if (this.spans_reserved == 0) {
                this.spans_reserved = got_count - span_count;
                this.span_reserve = subspan;
                this.span_reserve_master = (RPSpan) subspan.list.list.get(0);
            }
            else {
                _memory_heap_cache_insert(subspan);
            }
            return span;
        }
                //Step 4: Extract from global cache
//                idx = span_count - 1;
//        this.span_cache[idx] = _memory_global_cache_extract(span_count);
//        if (this.span_cache[idx] != null) {
////            this.global_to_thread += this.span_cache[idx].list.getFirst().data.listSize * span_count * _memory_span_size;
//            return span_cache[idx]._memory_span_list_pop();
//        }
        return null;
    }

//! Allocate a small/medium sized memory block from the given heap
void
    _memory_allocate_from_heap(RPByteBuf<T> buf, int size) {
        //Calculate the size class index and do a dependent lookup of the final class index (in case of merged classes)
	 int base_idx = (size <= SMALL_SIZE_LIMIT) ?
                ((size + (SMALL_GRANULARITY - 1)) >> SMALL_GRANULARITY_SHIFT) :
                SMALL_CLASS_COUNT + ((size - SMALL_SIZE_LIMIT + (MEDIUM_GRANULARITY - 1)) >> MEDIUM_GRANULARITY_SHIFT);
     assert((base_idx != 0) || ((base_idx - 1) < SIZE_CLASS_COUNT));
	 int class_idx = _memory_size_class[base_idx == 0 ? (base_idx - 1) : 0].class_idx;

     RPSpanBlock active_block = this.active_block[class_idx];
     RPSizeClass size_class = _memory_size_class[class_idx];
	 int class_size = size_class.size;

        //Step 1: Try to get a block from the currently active span. The span block bookkeeping
        //        data for the active span is stored in the heap for faster access
        use_active:
        for (int i = 0; i <1 ; i++) { // instead of goto
            for (int j = 0; j <1; j++) {
                if (active_block.free_count != 0) {
                    //Happy path, we have a span with at least one free block
                    RPSpan span = this.active_span[class_idx];
                    int offset = class_size * active_block.free_list;
                    assert (span != null && (span.heap_id == this.id));

                    if (active_block.free_count == 1) {
                        //Span is now completely allocated, set the bookkeeping data in the
                        //span itself and reset the active span pointer in the heap
                        span.data.block.free_count = active_block.free_count = 0;
                        span.data.block.first_autolink = 0xFFFF;
                        this.active_span[class_idx] = span;//);// = (RPSpan) null;
                    } else {
                        //Get the next free block, either from linked list or from auto link
                        ++active_block.free_list;
                        if (active_block.free_list <= active_block.first_autolink)
                            active_block.free_list = offset;
                        assert (active_block.free_list < size_class.block_count);
                        --active_block.free_count;
                    }
                    buf.handle = offset;
                    buf.initUnpooled(span,size,_memory_medium_size_limit);
                    return;
                }

                //Step 2: No active span, try executing deferred deallocations and try again if there
                //        was at least one of the requested size class
                _memory_deallocate_deferred();

                //Step 3: Check if there is a semi-used span of the requested size class available
                //this part of code is working, but it is not used in tests
//                if (this.size_cache[class_idx] != null) {
//                    //Promote a pending semi-used span to be active, storing bookkeeping data in
//                    //the heap structure for faster access
//                    RPSpanList spanlist = this.size_cache[class_idx];
//                    RPSpan span = spanlist.list.getFirst();
//                    //Mark span as owned by this heap
//                    span.heap_id = this.id;
////            atomic_thread_fence_release();
//
//                    active_block = span.data.block;
//                    assert (active_block.free_count > 0);
//                    this.size_cache[class_idx] = spanlist;
//                    this.active_span[class_idx] = span;//);
//
//                    continue use_active;
//                }
            }
        }

        //Step 4: Find a span in one of the cache levels
        RPSpan span = _memory_heap_cache_extract(1);
        if (span == null) {
            //Step 5: Map in more virtual memory
            span = _memory_map_spans(1);
        }

        //Mark span as owned by this heap and set base data
        assert(span.span_count == 1);
        span.size_class = class_idx;
        span.heap_id = this.id;
//        atomic_thread_fence_release();

        //If we only have one block we will grab it, otherwise
        //set span as new span to use for next allocation
        if (size_class.block_count > 1) {
            //Reset block order to sequential auto linked order
            active_block.free_count = (size_class.block_count - 1);
            active_block.free_list = 1;
            active_block.first_autolink = 1;
            this.active_span[class_idx] = span;//);
        }
        else {
            span.data.block.free_count = 0;
            span.data.block.first_autolink = 0xFFFF;
        }

        buf.initUnpooled(span,size,_memory_medium_size_limit);
        //Return first block if memory page span
//        return pointer_offset(span, SPAN_HEADER_SIZE);
    }

//! Allocate a large sized memory block from the given heap
void
    _memory_allocate_large_from_heap(RPByteBuf<T> buf, int size) {
        //Calculate number of needed max sized spans (including header)
        //Since this function is never called if size > LARGE_SIZE_LIMIT
        //the span_count is guaranteed to be <= LARGE_CLASS_COUNT
        int span_count = size >> _memory_span_size_shift;
        if ((size & (_memory_span_size - 1)) != 0)
            ++span_count;
        int idx = span_count - 1;

        //Step 1: Find span in one of the cache levels
        RPSpan span = _memory_heap_cache_extract(span_count);
        if (span == null) {
            //Step 2: Map in more virtual memory
            span = _memory_map_spans(span_count);
        }

        //Mark span as owned by this heap and set base data
        assert(span.span_count == span_count);
        span.size_class = SIZE_CLASS_COUNT + idx;
        span.heap_id = this.id;
        buf.initUnpooled(span,size,LARGE_SIZE_LIMIT);
//        atomic_thread_fence_release();

//        return pointer_offset(span, SPAN_HEADER_SIZE);
    }

////! Allocate a new heap
//    static RPThreadHeap
//    _memory_allocate_heap() {
//        RPThreadHeap raw_heap;
//        RPThreadHeap next_raw_heap;
//        int orphan_counter;
//        RPThreadHeap heap;
//        RPThreadHeap next_heap;
////        Try getting an orphaned heap
////        atomic_thread_fence_acquire();
//        do {
//            raw_heap = _memory_orphan_heaps;
//            heap = raw_heap;// & ~0xFF;
//            if (heap == null)
//                break;
//            next_heap = heap.next_orphan;
//            orphan_counter = _memory_orphan_counter.incrementAndGet();
//            next_raw_heap = next_heap | (orphan_counter & 0xFF);
//        }
//        while (!atomic_cas_ptr(&_memory_orphan_heaps, next_raw_heap, raw_heap));
//
//        if (heap == null) {
//            //Map in pages for a new heap
//            int align_offset = 0;
//            heap = new RPThreadHeap();
//            heap.align_offset = align_offset;
//
//            //Get a new heap ID
//            do {
//                heap.id = _memory_heap_id.incrementAndGet();
//                if (_memory_heap_lookup(heap.id))
//                    heap.id = 0;
//            } while (heap.id == 0);
//
//            //Link in heap in heap ID map
//            int list_idx = heap.id % HEAP_ARRAY_SIZE;
//            do {
//                next_heap = _memory_heaps[list_idx];
//                heap.next_heap = next_heap;
//            } while (!atomic_cas_ptr(&_memory_heaps[list_idx], heap, next_heap));
//        }
//
//        //Clean up any deferred operations
//        heap._memory_deallocate_deferred();
//
//        return heap;
//    }

    //! Deallocate the given small/medium memory block from the given heap
    void
    _memory_deallocate_to_heap(RPSpan span, ByteBuf p) {
        //Check if span is the currently active span in order to operate
        //on the correct bookkeeping data
        assert(span.span_count == 1);
	    int class_idx = span.size_class;
        RPSizeClass size_class = _memory_size_class[class_idx];
        boolean is_active = (this.active_span[class_idx] == span);
        RPSpanBlock block_data = is_active ?
                this.active_block[class_idx] :
	                           span.data.block;

        //Check if the span will become completely free
        if (block_data.free_count == (size_class.block_count - 1)) {
            //If it was active, reset counter. Otherwise, if not active, remove from
            //partial free list if we had a previous free block (guard for classes with only 1 block)
            if (is_active)
                block_data.free_count = 0;
            else if (block_data.free_count > 0)
                this.size_cache[class_idx]._memory_span_list_doublelink_remove(span);

            //Add to heap span cache
            _memory_heap_cache_insert(span);
            return;
        }

        //Check if first free block for this span (previously fully allocated)
        if (block_data.free_count == 0) {
            //add to free list and disable autolink
            this.size_cache[class_idx]._memory_span_list_doublelink_add(span);
            block_data.first_autolink = 0xFFFF;
        }
        ++block_data.free_count;
        //Span is not yet completely free, so add block to the linked list of free blocks
//	    void* blocks_start = pointer_offset(span, SPAN_HEADER_SIZE);
        int block_offset = p.arrayOffset();
        int block_idx = block_offset / size_class.size;
//        int block = block_idx * size_class.size;
//	    block = block_data.free_list;
        if (block_data.free_list > block_data.first_autolink)
            block_data.first_autolink = block_data.free_list;
        block_data.free_list = block_idx;
    }

    //! Deallocate the given large memory block to the given heap
    void
    _memory_deallocate_large_to_heap(RPSpan span) {
        //Decrease counter
        assert(span.span_count == (span.size_class - SIZE_CLASS_COUNT + 1));
        assert(span.size_class >= SIZE_CLASS_COUNT);
        assert(span.size_class - SIZE_CLASS_COUNT < LARGE_CLASS_COUNT);
        assert((span.flags & RPSpan.SPAN_FLAG_MASTER) == 0 || (span.flags & SPAN_FLAG_SUBSPAN) == 0);
        assert((span.flags & RPSpan.SPAN_FLAG_MASTER) != 0 || (span.flags & SPAN_FLAG_SUBSPAN) != 0);
        if ((span.span_count > 1) && (this.spans_reserved != 0)) {
            this.span_reserve = span;
            this.spans_reserved = span.span_count;
            if ((span.flags & RPSpan.SPAN_FLAG_MASTER) != 0) {
                this.span_reserve_master = span;
            }
            else { //SPAN_FLAG_SUBSPAN
                RPSpan master = (RPSpan) span.list.list.get(0);
                this.span_reserve_master = master;
                assert((master.flags & RPSpan.SPAN_FLAG_MASTER) != 0);
//                assert(master.remaining_spans >= span.span_count);
            }
        }
        else {
            //Insert into cache list
            _memory_heap_cache_insert(span);
        }
    }

    //! Process pending deferred cross-thread deallocations
    void
    _memory_deallocate_deferred() {
        //Grab the current list of deferred deallocations
//        atomic_thread_fence_acquire();
//	    void* p = this.defer_deallocate;
//        if (!p || !atomic_cas_ptr(this.defer_deallocate, 0, p))
//            return;
//        do {
//		    void* next = *(void**)p;
//            RPSpan span = (void*)(p & _memory_span_mask);
//            _memory_deallocate_to_heap(this, span, p);
//            p = next;
//        } while (p);
    }

    //! Defer deallocation of the given block to the given heap
    static void
    _memory_deallocate_defer(int heap_id, ByteBuf p) {
        //Get the heap and link in pointer in list of deferred operations
//        RPThreadHeap heap = _memory_heap_lookup(heap_id);
//        if (heap == null)
//            return;
//	    void* last_ptr;
//        do {
//            last_ptr = heap.defer_deallocate;
//		*(void**)p = last_ptr; //Safe to use block, it's being deallocated
//        } while (!atomic_cas_ptr(heap.defer_deallocate, p, last_ptr));
    }


    //! Allocate a block of the given size
    void
    _memory_allocate(RPByteBuf<T> buf, int size) {
        if (size <= _memory_medium_size_limit) {
            _memory_allocate_from_heap(buf, size);
            return;
        }
        else if (size <= LARGE_SIZE_LIMIT) {
            _memory_allocate_large_from_heap(buf, size);
            return;
        }

        //Oversized, allocate pages directly
        int num_pages = size >> _memory_page_size_shift;
        if ((size & (_memory_page_size - 1)) != 0)
            ++num_pages;
        int align_offset = 0;
        RPSpan span = new RPSpan(this, PlatformDependent.allocateUninitializedArray(size), span_cache[size], _memory_page_size, _memory_span_size);// _memory_map(num_pages * _memory_page_size, &align_offset);
        span.heap_id = new AtomicInteger(0);
        //Store page count in span_count
        span.span_count = num_pages;
        span.align_offset = align_offset;

        return ;
    }

    //! Deallocate the given block
    void
    _memory_deallocate(RPByteBuf<T> buf) {
        if (buf == null)
            return;

        //Grab the span (always at start of span, using span alignment)
        RPSpan span = buf.span;
        AtomicInteger heap_id = span.heap_id;
        if (heap_id.get() != 0)  {
            if (span.size_class < SIZE_CLASS_COUNT) {
                //Check if block belongs to this heap or if deallocation should be deferred
                if (this.id == heap_id)
                    _memory_deallocate_to_heap(span, buf);
                else
                    _memory_deallocate_defer(heap_id.get(), buf);
            }
            else {
                //Large blocks can always be deallocated and transferred between heaps
                _memory_deallocate_large_to_heap(span);
            }
        }
        else {
            //Oversized allocation, page count is stored in span_count
            int num_pages = span.span_count;
//            _memory_unmap(span, num_pages * _memory_page_size, span->align_offset, num_pages * _memory_page_size);
        }
    }

//! Reallocate the given block to the given size
     void
    _memory_reallocate(RPByteBuf p, int size, int oldsize, int flags) {
        if (p != null) {
            //Grab the span using guaranteed span alignment
            RPSpan span = p.span;
            AtomicInteger heap_id = span.heap_id;
            if (heap_id.get() != 0) {
                if (span.size_class < SIZE_CLASS_COUNT) {
                    //Small/medium sized block
                    assert(span.span_count == 1);
                    RPSizeClass size_class = _memory_size_class[span.size_class];
                    int block_offset = span.list.list.indexOf(p);
                    int block_idx = block_offset / size_class.size;
//				    void* block = pointer_offset(blocks_start, block_idx * size_class.size);
                    if (size_class.size >= size)
                        return ; //Still fits in block, never mind trying to save memory
                    if (oldsize == 0)
                        oldsize = size_class.size - p.offset;// block;
                }
                else {
                    //Large block
                    int total_size = size;
                    int num_spans = total_size >> _memory_span_size_shift;
                    if ((total_size & (_memory_span_mask - 1)) != 0)
                        ++num_spans;
                    int current_spans = (span.size_class - SIZE_CLASS_COUNT) + 1;
                    assert(current_spans == span.span_count);
                    if ((current_spans >= num_spans) && (num_spans >= (current_spans / 2)))
                        return; //Still fits and less than half of memory would be freed
                    if (oldsize == 0)
                        oldsize = (current_spans * _memory_span_size) - span.list.list.indexOf(p);
                }
            }
            else {
                //Oversized block
                int total_size = size;
                int num_pages = total_size >> _memory_page_size_shift;
                if ((total_size & (_memory_page_size - 1)) != 0)
                    ++num_pages;
                //Page count is stored in span_count
                int current_pages = span.span_count;
//			    void* block = span.memory;
                if ((current_pages >= num_pages) && (num_pages >= (current_pages / 2)))
                    return; //Still fits and less than half of memory would be freed
                if (oldsize == 0)
                    oldsize = (current_pages * _memory_page_size) - span.list.list.indexOf(p);
            }
        }

        //Size is greater than block size, need to allocate a new block and deallocate the old
        //Avoid hysteresis by overallocating if increase is small (below 37%)
        int lower_bound = oldsize + (oldsize >> 2) + (oldsize >> 3);
	    _memory_allocate(p, (size > lower_bound) ? size : ((size > oldsize) ? lower_bound : size));
//        if (p) {
////            if (!(flags & RPMALLOC_NO_PRESERVE))
//                memoryCopy(block, 0, p, 0, oldsize < size ? oldsize : size);
//            _memory_deallocate(p);
//        }

        return;
    }

    //! Get the usable size of the given block
    int
    _memory_usable_size(RPByteBuf p) {
        //Grab the span using guaranteed span alignment
        RPSpan span = p.span;
        AtomicInteger heap_id = span.heap_id;
        if (heap_id.get() != 0) {
            //Small/medium block
            if (span.size_class < SIZE_CLASS_COUNT) {
                RPSizeClass size_class = _memory_size_class[span.size_class];
//			    void* blocks_start = span.memory;
                return size_class.size - (p.offset % size_class.size);
            }

            //Large block
            int current_spans = (span.size_class - SIZE_CLASS_COUNT) + 1;
            return (current_spans * _memory_span_size) - span.list.list.indexOf(p);
        }

        //Oversized block, page count is stored in span_count
        int current_pages = span.span_count;
        return (current_pages * _memory_page_size) - span.list.list.indexOf(p);
    }

//    //! Adjust and optimize the size class properties for the given class
//    void
//    _memory_adjust_size_class(int iclass) {
//        int block_size = _memory_size_class[iclass].size;
//        int block_count = (_memory_span_size) / block_size;
//
//        _memory_size_class[iclass].block_count = block_count;
//        _memory_size_class[iclass].class_idx = iclass;
//
//        //Check if previous size classes can be merged
//        int prevclass = iclass;
//        while (prevclass > 0) {
//            --prevclass;
//            //A class can be merged if number of pages and number of blocks are equal
//            if (_memory_size_class[prevclass].block_count == _memory_size_class[iclass].block_count) {
//                memoryCopy(_memory_size_class[prevclass], 0, _memory_size_class[iclass], 0 , _memory_size_class[iclass].size);
//            }
//            else {
//                break;
//            }
//        }
//    }

    protected RPByteBuf<T> newByteBuf(int maxCapacity) { return null; }
    boolean isDirect() { return false; }
    protected void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length) { return; }

    RPByteBuf<T> allocate(int reqCapacity, int maxCapacity) {
        RPByteBuf<T> buf = newByteBuf(maxCapacity);
        _memory_allocate(buf, reqCapacity);
        return buf;
    }

    static final class RPHeapHeap extends RPThreadHeap<byte[]> {
        public RPHeapHeap(RPByteBufAllocator parent) {
            super(parent);
        }

//        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
//                  int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
//            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
//                    directMemoryCacheAlignment);
//        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected RPByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return RPHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class RPDirectHeap extends RPThreadHeap<ByteBuffer> {
        public RPDirectHeap(RPByteBufAllocator parent) {
            super(parent);
        }

//        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
//                    int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
//            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
//                    directMemoryCacheAlignment);
//        }

        @Override
        boolean isDirect() {
            return true;
        }

        @Override
        protected RPByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            return RPDirectByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }

}
