/*
 * Shared syscall stub for patched static C programs.
 *
 * A static C program (glibc, musl, ...) makes its system calls itself, so
 * LD_PRELOAD cannot reach it. native/gopatch/patchc.go finds every "svc #0" in
 * the executable segment and replaces it with a five-instruction trampoline:
 *
 *     str x30, [sp, #-16]!     save the link register the svc would keep
 *     bl  oc_c_stub            enter this stub
 *     bti c                    landing pad for the return
 *     ldr x30, [sp], #16       restore the link register
 *     b   <site + 4>           continue after the original svc
 *
 * The trampoline runs with the syscall number in x8 and the arguments in
 * x0-x5, exactly as the kernel expects. This stub moves the number to x6 (the
 * seventh C argument of oc_stub_entry), calls oc_stub_entry, and returns to the
 * trampoline. The kernel only changes x0 across a system call, so every
 * register the kernel preserves (x1-x18, x29, x30, NZCV) is saved and restored
 * here; the trampoline already saved the caller's x30 on the stack.
 *
 * The stub is linked into the same flat blob as oc_stub_entry (see gosys.ld and
 * build.sh), so the "bl" displacement stays valid when the blob is copied to
 * the target program. It uses no adrp, no relocations and no writable data.
 */

    .section .text.oc_c_stub,"ax",%progbits
    .global oc_c_stub
    .type oc_c_stub, %function
    .p2align 2
oc_c_stub:
    stp x1, x2, [sp, #-176]!
    stp x3, x4, [sp, #16]
    stp x5, x6, [sp, #32]
    stp x7, x8, [sp, #48]
    stp x9, x10, [sp, #64]
    stp x11, x12, [sp, #80]
    stp x13, x14, [sp, #96]
    stp x15, x16, [sp, #112]
    stp x17, x18, [sp, #128]
    stp x29, x30, [sp, #144]
    mrs x9, nzcv
    str x9, [sp, #160]
    mov x6, x8
    bl oc_stub_entry
    ldr x9, [sp, #160]
    msr nzcv, x9
    ldp x29, x30, [sp, #144]
    ldp x17, x18, [sp, #128]
    ldp x15, x16, [sp, #112]
    ldp x13, x14, [sp, #96]
    ldp x11, x12, [sp, #80]
    ldp x9, x10, [sp, #64]
    ldp x7, x8, [sp, #48]
    ldp x5, x6, [sp, #32]
    ldp x3, x4, [sp, #16]
    ldp x1, x2, [sp], #176
    ret
    .size oc_c_stub, .-oc_c_stub
