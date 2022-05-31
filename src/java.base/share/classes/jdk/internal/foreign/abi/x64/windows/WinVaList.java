/*
 *  Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.  Oracle designates this
 *  particular file as subject to the "Classpath" exception as provided
 *  by Oracle in the LICENSE file that accompanied this code.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */
package jdk.internal.foreign.abi.x64.windows;

import java.lang.foreign.*;

import jdk.internal.foreign.MemorySessionImpl;
import jdk.internal.foreign.Scoped;
import jdk.internal.foreign.abi.SharedUtils;
import jdk.internal.foreign.abi.SharedUtils.SimpleVaArg;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static jdk.internal.foreign.PlatformLayouts.Win64.C_POINTER;

// see vadefs.h (VC header)
//
// in short
// -> va_list is just a pointer to a buffer with 64 bit entries.
// -> non-power-of-two-sized, or larger than 64 bit types passed by reference.
// -> other types passed in 64 bit slots by normal function calling convention.
//
// X64 va_arg impl:
//
//    typedef char* va_list;
//
//    #define __crt_va_arg(ap, t)                                               \
//        ((sizeof(t) > sizeof(__int64) || (sizeof(t) & (sizeof(t) - 1)) != 0) \
//            ? **(t**)((ap += sizeof(__int64)) - sizeof(__int64))             \
//            :  *(t* )((ap += sizeof(__int64)) - sizeof(__int64)))
//
public non-sealed class WinVaList implements VaList, Scoped {
    public static final Class<?> CARRIER = MemoryAddress.class;
    private static final long VA_SLOT_SIZE_BYTES = 8;
    private static final VarHandle VH_address = C_POINTER.varHandle();

    private static final VaList EMPTY = new SharedUtils.EmptyVaList(MemoryAddress.NULL);

    private MemorySegment segment;
    private final MemorySession session;

    private WinVaList(MemorySegment segment, MemorySession session) {
        this.segment = segment;
        this.session = session;
    }

    public static final VaList empty() {
        return EMPTY;
    }

    @Override
    public int nextVarg(ValueLayout.OfInt layout) {
        return (int) read(int.class, layout);
    }

    @Override
    public long nextVarg(ValueLayout.OfLong layout) {
        return (long) read(long.class, layout);
    }

    @Override
    public double nextVarg(ValueLayout.OfDouble layout) {
        return (double) read(double.class, layout);
    }

    @Override
    public MemoryAddress nextVarg(ValueLayout.OfAddress layout) {
        return (MemoryAddress) read(MemoryAddress.class, layout);
    }

    @Override
    public MemorySegment nextVarg(GroupLayout layout, SegmentAllocator allocator) {
        Objects.requireNonNull(allocator);
        return (MemorySegment) read(MemorySegment.class, layout, allocator);
    }

    private Object read(Class<?> carrier, MemoryLayout layout) {
        return read(carrier, layout, SharedUtils.THROWING_ALLOCATOR);
    }

    private Object read(Class<?> carrier, MemoryLayout layout, SegmentAllocator allocator) {
        Objects.requireNonNull(layout);
        Object res;
        if (carrier == MemorySegment.class) {
            TypeClass typeClass = TypeClass.typeClassFor(layout, false);
            res = switch (typeClass) {
                case STRUCT_REFERENCE -> {
                    MemoryAddress structAddr = (MemoryAddress) VH_address.get(segment);
                    MemorySegment struct = MemorySegment.ofAddress(structAddr, layout.byteSize(), session());
                    MemorySegment seg = allocator.allocate(layout);
                    seg.copyFrom(struct);
                    yield seg;
                }
                case STRUCT_REGISTER ->
                    allocator.allocate(layout).copyFrom(segment.asSlice(0, layout.byteSize()));
                default -> throw new IllegalStateException("Unexpected TypeClass: " + typeClass);
            };
        } else {
            VarHandle reader = layout.varHandle();
            res = reader.get(segment);
        }
        segment = segment.asSlice(VA_SLOT_SIZE_BYTES);
        return res;
    }

    @Override
    public void skip(MemoryLayout... layouts) {
        Objects.requireNonNull(layouts);
        MemorySessionImpl.checkValidState(session);
        Stream.of(layouts).forEach(Objects::requireNonNull);
        segment = segment.asSlice(layouts.length * VA_SLOT_SIZE_BYTES);
    }

    static WinVaList ofAddress(MemoryAddress addr, MemorySession session) {
        MemorySegment segment = MemorySegment.ofAddress(addr, Long.MAX_VALUE, session);
        return new WinVaList(segment, session);
    }

    static Builder builder(MemorySession session) {
        return new Builder(session);
    }

    @Override
    public MemorySessionImpl session() {
        return (MemorySessionImpl)session;
    }

    @Override
    public VaList copy() {
        MemorySessionImpl.checkValidState(session);
        return new WinVaList(segment, session);
    }

    @Override
    public MemoryAddress address() {
        return segment.address();
    }

    public static non-sealed class Builder implements VaList.Builder {

        private final MemorySession session;
        private final List<SimpleVaArg> args = new ArrayList<>();

        public Builder(MemorySession session) {
            MemorySessionImpl.checkValidState(session);
            this.session = session;
        }

        private Builder arg(Class<?> carrier, MemoryLayout layout, Object value) {
            Objects.requireNonNull(layout);
            Objects.requireNonNull(value);
            args.add(new SimpleVaArg(carrier, layout, value));
            return this;
        }

        @Override
        public Builder addVarg(ValueLayout.OfInt layout, int value) {
            return arg(int.class, layout, value);
        }

        @Override
        public Builder addVarg(ValueLayout.OfLong layout, long value) {
            return arg(long.class, layout, value);
        }

        @Override
        public Builder addVarg(ValueLayout.OfDouble layout, double value) {
            return arg(double.class, layout, value);
        }

        @Override
        public Builder addVarg(ValueLayout.OfAddress layout, Addressable value) {
            return arg(MemoryAddress.class, layout, value.address());
        }

        @Override
        public Builder addVarg(GroupLayout layout, MemorySegment value) {
            return arg(MemorySegment.class, layout, value);
        }

        public VaList build() {
            if (args.isEmpty()) {
                return EMPTY;
            }
            SegmentAllocator allocator = SegmentAllocator.newNativeArena(session);
            MemorySegment segment = allocator.allocate(VA_SLOT_SIZE_BYTES * args.size());
            List<MemorySegment> attachedSegments = new ArrayList<>();
            attachedSegments.add(segment);
            MemorySegment cursor = segment;

            for (SimpleVaArg arg : args) {
                if (arg.carrier == MemorySegment.class) {
                    MemorySegment msArg = ((MemorySegment) arg.value);
                    TypeClass typeClass = TypeClass.typeClassFor(arg.layout, false);
                    switch (typeClass) {
                        case STRUCT_REFERENCE -> {
                            MemorySegment copy = allocator.allocate(arg.layout);
                            copy.copyFrom(msArg); // by-value
                            attachedSegments.add(copy);
                            VH_address.set(cursor, copy.address());
                        }
                        case STRUCT_REGISTER ->
                            cursor.copyFrom(msArg.asSlice(0, VA_SLOT_SIZE_BYTES));
                        default -> throw new IllegalStateException("Unexpected TypeClass: " + typeClass);
                    }
                } else {
                    VarHandle writer = arg.varHandle();
                    writer.set(cursor, arg.value);
                }
                cursor = cursor.asSlice(VA_SLOT_SIZE_BYTES);
            }

            return new WinVaList(segment, session);
        }
    }
}
