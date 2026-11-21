/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Minimal freestanding C11 <stdatomic.h> shim built on Clang's native
 * __c11_atomic_* builtins. Avoids Android NDK's patched stdatomic.h
 * pulling in Bionic <sys/cdefs.h> when compiling with -ffreestanding -nostdinc.
 */
#ifndef REKORDER_SHIM_STDATOMIC_H
#define REKORDER_SHIM_STDATOMIC_H

typedef enum memory_order {
    memory_order_relaxed = __ATOMIC_RELAXED,
    memory_order_consume = __ATOMIC_CONSUME,
    memory_order_acquire = __ATOMIC_ACQUIRE,
    memory_order_release = __ATOMIC_RELEASE,
    memory_order_acq_rel = __ATOMIC_ACQ_REL,
    memory_order_seq_cst = __ATOMIC_SEQ_CST
} memory_order;

#define atomic_init(obj, val) __c11_atomic_init((obj), (val))
#define atomic_load_explicit(obj, order) __c11_atomic_load((obj), (order))
#define atomic_store_explicit(obj, val, order) __c11_atomic_store((obj), (val), (order))
#define atomic_fetch_add_explicit(obj, arg, order) __c11_atomic_fetch_add((obj), (arg), (order))

#endif /* REKORDER_SHIM_STDATOMIC_H */
