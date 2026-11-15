package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"sort"
)

// A static C program (glibc, musl, ...) does not use libc through a dynamic
// loader, so LD_PRELOAD cannot reach it. It makes its system calls itself with
// "svc #0", exactly like a Go program, but there are many call sites instead of
// one. This file finds every site in the executable segments and redirects it
// to the same oc-gosys blob through a small trampoline.
//
// At any "svc #0" the syscall number is in x8 and the arguments in x0-x5, which
// is the AArch64 Linux syscall ABI for every libc. The trampoline therefore
// needs no knowledge of the number: it saves x30, calls the shared oc_c_stub
// (which moves x8 to the seventh argument of oc_stub_entry), and jumps back to
// the instruction after the original svc.
//
// Two sites are left alone:
//   - clone (220) switches to a new stack, so the trampoline's saved x30 would
//     be read from the wrong stack in the child;
//   - rt_sigreturn (139) never returns to the instruction after the svc.
//
// glibc also has cancellation wrappers (__internal_syscall_cancel,
// __syscall_cancel_arch) that load the number dynamically; the decoder cannot
// see their number, and those sites are patched like any other.

const (
	svcInsn    = 0xd4000001
	trampBytes = 20 // five instructions per site

	sysClone       = 220
	sysRtSigreturn = 139

	maxCSites  = 8192
	maxCOutput = 1 << 30
)

var errNotC = errors.New("not a patchable AArch64 static program")

type cSite struct {
	off   uint64
	vaddr uint64
	nr    int
}

// findCStaticSites returns every "svc #0" in the executable PT_LOAD segments,
// except the clone and rt_sigreturn sites that cannot be redirected.
func findCStaticSites(data []byte, img *elfImage) []cSite {
	var sites []cSite
	for _, p := range img.Progs {
		if p.Type != ptLoad || p.Flags&pfX == 0 || p.Filesz < 4 {
			continue
		}
		if p.Off+p.Filesz > uint64(len(data)) {
			continue
		}
		words := p.Filesz / 4
		for k := uint64(0); k < words; k++ {
			off := p.Off + k*4
			if binary.LittleEndian.Uint32(data[off:]) != svcInsn {
				continue
			}
			nr := decodeSyscallNr(data, p.Off, k)
			if nr == sysClone || nr == sysRtSigreturn {
				continue
			}
			sites = append(sites, cSite{off: off, vaddr: p.Vaddr + k*4, nr: nr})
		}
	}
	return sites
}

// decodeSyscallNr looks back from the svc at instruction index [i] for a
// "mov x8, #imm" and returns the number. It returns -1 when the number is not a
// simple immediate (glibc's cancellation wrappers move it from another
// register) or when a branch ends the search.
func decodeSyscallNr(data []byte, segOff, i uint64) int {
	for k := uint64(1); k <= 12; k++ {
		if k > i {
			break
		}
		w := binary.LittleEndian.Uint32(data[segOff+(i-k)*4:])
		rd := w & 0x1f
		switch {
		case (w&0xff800000 == 0xd2800000 || w&0xff800000 == 0x52800000) && rd == 8 && (w>>21)&3 == 0:
			// movz x8, #imm16 (or the 32-bit form).
			return int((w >> 5) & 0xffff)
		case (w&0xff800000 == 0x92800000 || w&0xff800000 == 0x12800000) && rd == 8 && (w>>21)&3 == 0:
			// movn x8, #imm16: a negative number, never clone/rt_sigreturn.
			return int(^(w >> 5) & 0xffff)
		case (w&0xffe0ffe0 == 0xaa0003e0 || w&0xffe0ffe0 == 0x2a0003e0) && rd == 8:
			// mov x8, xN: the number comes from a register.
			return -1
		}
		if isBarrier(w) {
			break
		}
	}
	return -1
}

// isBarrier reports whether [w] ends the basic block, so the search for the
// number must stop.
func isBarrier(w uint32) bool {
	switch {
	case w&0xfc000000 == 0x14000000: // b
		return true
	case w&0xfc000000 == 0x94000000: // bl
		return true
	case w&0xff000010 == 0x54000000: // b.cond
		return true
	case w&0x7e000000 == 0x34000000: // cbz/cbnz
		return true
	case w&0x7e000000 == 0x36000000: // tbz/tbnz
		return true
	case w&0xfffffc1f == 0xd65f0000: // ret
		return true
	case w&0xfffffc1f == 0xd61f0000: // br
		return true
	case w&0xfffffc1f == 0xd63f0000: // blr
		return true
	case w&0xffe0001f == 0xd4000001: // svc
		return true
	case w&0xfffffc1f == 0xd503241f: // bti
		return true
	case w == 0xd503233f: // paciasp
		return true
	case w == 0xd50323bf: // autiasp
		return true
	}
	return false
}

// patchCData returns a new image with the oc-gosys blob appended after the last
// load segment and every syscall site redirected to it. [data] is not modified.
func patchCData(data []byte, img *elfImage) ([]byte, error) {
	if img.Machine != emAArch64 {
		return nil, errNotC
	}
	sites := findCStaticSites(data, img)
	if len(sites) == 0 {
		return nil, errNotC
	}
	if len(sites) > maxCSites {
		return nil, fmt.Errorf("found %d syscall sites, refusing to patch", len(sites))
	}

	var order []int
	for i, p := range img.Progs {
		if p.Type == ptLoad {
			order = append(order, i)
		}
	}
	sort.Sort(loadOrderByVaddr{img.Progs, order})
	if len(order) == 0 {
		return nil, errNotC
	}
	last := order[len(order)-1]
	seg := &img.Progs[last]
	if seg.Flags&pfW == 0 {
		return nil, errors.New("the last load segment is not writable")
	}
	// The cave is appended at the end of the file and mapped by extending the
	// last segment by virtual address. That is only safe when the same segment
	// also ends last in the file; otherwise the new file bytes would land in
	// the middle of another segment's data.
	for _, i := range order {
		p := &img.Progs[i]
		if i != last && p.Off+p.Filesz > seg.Off+seg.Filesz {
			return nil, errors.New("the last load segment does not end the file")
		}
	}

	blen := uint64(len(gosysBlob))
	if blen == 0 || blen > 1<<20 {
		return nil, errors.New("the embedded oc-gosys blob is missing")
	}
	if !bytes.HasPrefix(gosysBlob, []byte("OCGOSYS1")) {
		return nil, errors.New("the embedded oc-gosys blob is malformed")
	}
	if gosysCStubOff <= 0 || uint64(gosysCStubOff) >= blen {
		return nil, errors.New("the embedded oc-gosys blob has no C stub")
	}

	caveOff := alignUp(uint64(len(data)), 64)
	if caveOff < seg.Off+seg.Filesz {
		return nil, errors.New("the appended code would overwrite the last segment")
	}
	caveV := seg.Vaddr + (caveOff - seg.Off)
	// The blob length is not a multiple of four; the trampolines must start on
	// an instruction boundary or their branch offsets truncate.
	trampBase := alignUp(caveV+blen, 4)
	trampLen := uint64(len(sites)) * trampBytes
	scratchV := alignUp(trampBase+trampLen, 64)
	scratchOff := caveOff + (scratchV - caveV)
	need := scratchOff + uint64(gosysScratchSize)
	if need > maxCOutput {
		return nil, errors.New("the patched program would be too large")
	}
	cstubV := caveV + uint64(gosysCStubOff)

	out := make([]byte, need)
	copy(out, data)
	// The bytes after the old end of the segment were the zero-filled bss. Now
	// the segment maps them from the file, so they must be cleared or the
	// program's globals would start with whatever followed the segment (the
	// section headers, for example).
	for i := seg.Off + seg.Filesz; i < need; i++ {
		out[i] = 0
	}
	copy(out[caveOff:caveOff+blen], gosysBlob)
	binary.LittleEndian.PutUint64(out[caveOff+blobScratchRelOff:], scratchV-caveV)

	for i := range sites {
		s := &sites[i]
		tv := trampBase + uint64(i)*trampBytes
		to := caveOff + (tv - caveV)
		binary.LittleEndian.PutUint32(out[to:], 0xf81f0ffe) // str x30, [sp, #-16]!
		bl, ok := branch(0x94000000, tv+4, cstubV)
		if !ok {
			return nil, errors.New("the shared stub is out of branch range")
		}
		binary.LittleEndian.PutUint32(out[to+4:], bl)
		binary.LittleEndian.PutUint32(out[to+8:], 0xd503245f)  // bti c
		binary.LittleEndian.PutUint32(out[to+12:], 0xf84107fe) // ldr x30, [sp], #16
		back, ok := branch(0x14000000, tv+16, s.vaddr+4)
		if !ok {
			return nil, errors.New("the call site is out of branch range")
		}
		binary.LittleEndian.PutUint32(out[to+16:], back)
		toSite, ok := branch(0x14000000, s.vaddr, tv)
		if !ok {
			return nil, errors.New("the trampoline is out of branch range")
		}
		binary.LittleEndian.PutUint32(out[s.off:], toSite)
	}

	// The segment becomes executable and grows over the blob, the trampolines
	// and the scratch area. The scratch is bss: it stays out of p_filesz.
	writeU32(out, seg.Pos+4, seg.Flags|pfX)
	writeU64(out, seg.Pos+32, scratchOff-seg.Off)
	writeU64(out, seg.Pos+40, scratchOff+uint64(gosysScratchSize)-seg.Off)
	return out, nil
}
