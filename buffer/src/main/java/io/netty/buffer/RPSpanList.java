package io.netty.buffer;

import io.netty.util.internal.PlatformDependent;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

public class RPSpanList {
    LinkedList<RPSpan> list;

    //! Size of a span of memory pages
    private static int _memory_span_size;
    //! Memory page size
    private static int _memory_page_size;
    RPThreadHeap heap;

    RPSpanList(int size, int _memory_page_size, int _memory_span_size, RPThreadHeap heap) {
        this.list = new LinkedList<RPSpan>();
        this._memory_span_size = _memory_span_size;
        this._memory_page_size = _memory_page_size;
        this.heap = heap;
        for (int i = 0; i < size ; i++) {
            list.add(new RPSpan(heap, PlatformDependent.allocateUninitializedArray(_memory_span_size), null, _memory_page_size,  _memory_span_size));
        }
    }
    //! Unmap a single linked list of spans
    void
    _memory_unmap_span_list() {
        list.forEach(RPSpan::_memory_unmap_span);
    }


    //! Add span to head of single linked span list
    int
    _memory_span_list_push(RPSpan span) {
        if (list.isEmpty()) {
            span.data.listSize = 1;
        } else {
            span.data.listSize = list.getFirst().data.listSize + 1;
        }
        list.offerFirst(span);
        return span.data.listSize;
    }

    //! Remove span from head of single linked span list, returns the new list head
    RPSpan
    _memory_span_list_pop() {
        RPSpan span = list.getFirst();
        list.remove(0);
        list.getFirst().data.listSize = span.data.listSize - 1;
        return span;
    }


    //! Split a single linked span list
    RPSpanList
    _memory_span_list_split(int limit) {
        RPSpanList next = new RPSpanList(0, _memory_page_size,  _memory_span_size, this.heap);
        if (limit < 2)
            limit = 2;

        if (list.getFirst().data.listSize > limit) {
            int list_size = 1;
            ListIterator<RPSpan> listIterator = list.listIterator();
            RPSpan current = null;
            if (!list.isEmpty()) {
                current = listIterator.next();
            }
            while (listIterator.hasNext() && list_size < limit) {
                current = listIterator.next();
                ++list_size;
            }
            while (listIterator.hasNext()) {
                next.list.add(current);
                listIterator.remove();
                current = listIterator.next();
            }

            next.list.getFirst().data.listSize = list.getFirst().data.listSize - list_size;
            list.getFirst().data.listSize = list_size;
        }
        return next;
    }

    //! Add a span to a double linked list
    void
    _memory_span_list_doublelink_add(RPSpan span) {
        list.offerFirst(span);
    }

    //! Remove a span from a double linked list
    void
    _memory_span_list_doublelink_remove(RPSpan span) {
        list.remove(span);
    }
}
