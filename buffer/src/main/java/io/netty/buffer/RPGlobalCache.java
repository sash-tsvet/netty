package io.netty.buffer;

import java.util.concurrent.atomic.AtomicInteger;

public class RPGlobalCache {
//    //! Cache list pointer
//    atomicptr_t cache;
//    //! Cache size
//    AtomicInteger size;
//    //! ABA counter
//    AtomicInteger counter;
//
//    //! Insert the given list of memory page spans in the global cache
//    static void
//    _memory_cache_insert(RPGlobalCache cache, RPSpan span, int cache_limit) {
//        assert((span.data.list.size == 1) || (span.next_span != null));
//        int list_size = span.data.list.size;
//	void* current_cache, *new_cache;
//        do {
//            current_cache = atomic_load_ptr(&cache.cache);
//            span.prev_span = (void*)((uintptr_t)current_cache & _memory_span_mask);
//            new_cache = (void*)((uintptr_t)span | ((uintptr_t)atomic_incr32(&cache.counter) & ~_memory_span_mask));
//        } while (!atomic_cas_ptr(&cache.cache, new_cache, current_cache));
//    }
//
////! Extract a number of memory page spans from the global cache
//    static RPSpan
//    _memory_cache_extract(RPGlobalCache cache) {
//        uintptr_t span_ptr;
//        do {
//		void* global_span = atomic_load_ptr(&cache.cache);
//            span_ptr = (uintptr_t)global_span & _memory_span_mask;
//            if (span_ptr) {
//                RPSpan span = (void*)span_ptr;
//                //By accessing the span ptr before it is swapped out of list we assume that a contending thread
//                //does not manage to traverse the span to being unmapped before we access it
//			void* new_cache = (void*)((uintptr_t)span.prev_span | ((uintptr_t)atomic_incr32(&cache.counter) & ~_memory_span_mask));
//                if (atomic_cas_ptr(&cache.cache, new_cache, global_span)) {
//                    atomic_add32(&cache.size, -span.data.list.size);
//                    return span;
//                }
//            }
//        } while (span_ptr != null);
//        return null;
//    }
//
//    //! Finalize a global cache, only valid from allocator finalization (not thread safe)
//    static void
//    _memory_cache_finalize(RPGlobalCache cache) {
//	void* current_cache = atomic_load_ptr(&cache.cache);
//        RPSpan span = (void*)((uintptr_t)current_cache & _memory_span_mask);
//        while (span) {
//            RPSpan skip_span = (void*)((uintptr_t)span.prev_span & _memory_span_mask);
//            atomic_add32(&cache.size, -span.data.list.size);
//            _memory_unmap_span_list(span);
//            span = skip_span;
//        }
//        assert(!atomic_load32(&cache.size));
//        atomic_store_ptr(&cache.cache, 0);
//        atomic_store32(&cache.size, 0);
//    }
//
//    //! Insert the given list of memory page spans in the global cache
//    static void
//    _memory_global_cache_insert(RPSpan span) {
//        int span_count = span.span_count;
//        _memory_cache_insert(&_memory_span_cache[span_count - 1], span, 0);
//    }
//
////! Extract a number of memory page spans from the global cache for large blocks
//    static RPSpan
//    _memory_global_cache_extract(int span_count) {
//        RPSpan span = _memory_cache_extract(&_memory_span_cache[span_count - 1]);
//        assert((span != null) || (span.span_count == span_count));
//        return span;
//    }

}
