package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

// The single place where a static Go program makes its system calls:
// internal/runtime/syscall.Syscall6. Seven loads fetch the trap number and the
// six arguments, then "svc #0" and "cmn x0, #4095". The site is the "svc".
var goSyscallPattern = []byte{
	0xe8, 0x07, 0x40, 0xf9, // ldr x8, [sp, #8]
	0xe0, 0x0b, 0x40, 0xf9, // ldr x0, [sp, #16]
	0xe1, 0x0f, 0x40, 0xf9, // ldr x1, [sp, #24]
	0xe2, 0x13, 0x40, 0xf9, // ldr x2, [sp, #32]
	0xe3, 0x17, 0x40, 0xf9, // ldr x3, [sp, #40]
	0xe4, 0x1b, 0x40, 0xf9, // ldr x4, [sp, #48]
	0xe5, 0x1f, 0x40, 0xf9, // ldr x5, [sp, #56]
	0x01, 0x00, 0x00, 0xd4, // svc #0
	0x1f, 0xfc, 0x3f, 0xb1, // cmn x0, #4095
}

var errNotGo = errors.New("not a patchable AArch64 Go program")

const (
	// Offsets inside the blob, see native/gosys/gosys.ld.
	blobThunkOff      = 0x20
	blobScratchRelOff = 16
)

/*******************************************************************************
 * Locating the call site
 ******************************************************************************/

// findGoSite returns the file offset and the virtual address of the "svc #0" of
// Syscall6. A binary can contain the sequence once only, in the executable part
// of a PT_LOAD segment.
func findGoSite(data []byte, img *elfImage) (int64, uint64, error) {
	var siteOff int64
	var siteV uint64
	found := 0
	for _, p := range img.Progs {
		if p.Type != ptLoad || p.Flags&pfX == 0 || p.Filesz < uint64(len(goSyscallPattern)) {
			continue
		}
		end := p.Off + p.Filesz
		if end > uint64(len(data)) {
			continue
		}
		seg := data[p.Off:end]
		start := 0
		for {
			i := bytes.Index(seg[start:], goSyscallPattern)
			if i < 0 {
				break
			}
			site := start + i + 28
			found++
			siteOff = int64(p.Off) + int64(site)
			siteV = p.Vaddr + uint64(site)
			start += i + 1
		}
	}
	if found == 0 {
		return 0, 0, errNotGo
	}
	if found > 1 {
		return 0, 0, fmt.Errorf("found %d Syscall6 call sites, expected one", found)
	}
	return siteOff, siteV, nil
}

/*******************************************************************************
 * Choosing the place for the added code
 ******************************************************************************/

type cave struct {
	seg   int // index into img.Progs
	vaddr uint64
	off   uint64
}

func alignUp(v, a uint64) uint64 { return (v + a - 1) &^ (a - 1) }

// findCave looks for a zero-filled gap between two PT_LOAD segments, which is
// where the Go linker leaves the padding of its page alignment. Only a gap after
// executable code is used: a writable data segment may be covered by RELRO and
// is made read-only by the loader after the relocations, and the code would not
// be executable. When no such gap exists, patchData appends the blob after the
// last segment instead.
func findCave(img *elfImage, order []int, total uint64) (cave, bool) {
	for k := 0; k+1 < len(order); k++ {
		s := &img.Progs[order[k]]
		next := &img.Progs[order[k+1]]
		if s.Flags&pfX == 0 || s.Memsz != s.Filesz || s.Filesz == 0 {
			continue
		}
		v := alignUp(s.Vaddr+s.Filesz, 64)
		off := s.Off + (v - s.Vaddr)
		// The next segment must start on its own page: otherwise the page
		// that holds the end of the blob is mapped again by that segment.
		if next.Vaddr&0xfff != 0 {
			continue
		}
		if v+total > next.Vaddr || off+total > next.Off {
			continue
		}
		if off+total > uint64(len(img.Data)) {
			continue
		}
		if !allZero(img.Data[s.Off+s.Filesz : off+total]) {
			continue
		}
		clash := false
		for _, i := range order {
			p := &img.Progs[i]
			if i == order[k] || p.Filesz == 0 {
				continue
			}
			if p.Off < off+total && off < p.Off+p.Filesz {
				clash = true
				break
			}
		}
		if clash {
			continue
		}
		return cave{seg: order[k], vaddr: v, off: off}, true
	}
	return cave{}, false
}

func allZero(b []byte) bool {
	for _, v := range b {
		if v != 0 {
			return false
		}
	}
	return true
}

/*******************************************************************************
 * Encoding the connections
 ******************************************************************************/

func branch(base uint32, from, to uint64) (uint32, bool) {
	delta := int64(to) - int64(from)
	if delta&3 != 0 || delta >= 1<<27 || delta < -(1<<27) {
		return 0, false
	}
	return base | uint32((delta>>2)&0x3ffffff), true
}

// thunkWords builds the code that stands between the call site and the entry:
// it saves the link register, passes the syscall number as the seventh C
// argument (x6, because the first eight integer arguments are in x0-x7), calls
// the entry, restores the link register and jumps back to the instruction after
// the svc.
func thunkWords(caveV, entryV, siteV uint64) ([7]uint32, error) {
	var words [7]uint32
	thunkV := caveV + blobThunkOff
	words[0] = 0xd10043ff // sub sp, sp, #16
	words[1] = 0xaa0803e6 // mov x6, x8      (seventh argument: syscall number)
	words[2] = 0xf90007fe // str x30, [sp, #8]
	bl, ok := branch(0x94000000, thunkV+12, entryV)
	if !ok {
		return words, errors.New("the added entry is out of branch range")
	}
	words[3] = bl
	words[4] = 0xf94007fe // ldr x30, [sp, #8]
	words[5] = 0x910043ff // add sp, sp, #16
	back, ok := branch(0x14000000, thunkV+24, siteV+4)
	if !ok {
		return words, errors.New("the call site is out of branch range")
	}
	words[6] = back
	return words, nil
}

/*******************************************************************************
 * Patching
 ******************************************************************************/

// patchData returns a new image with the oc-gosys blob added to a code gap and
// Syscall6 redirected to it. [data] is not modified.
func patchData(data []byte, img *elfImage) ([]byte, error) {
	if img.Machine != emAArch64 {
		return nil, errNotGo
	}
	siteOff, siteV, err := findGoSite(data, img)
	if err != nil {
		return nil, err
	}

	var order []int
	for i, p := range img.Progs {
		if p.Type == ptLoad {
			order = append(order, i)
		}
	}
	sort.Sort(loadOrderByVaddr{img.Progs, order})
	if len(order) < 2 {
		return nil, errNotGo
	}

	total := uint64(len(gosysBlob))
	if total == 0 || total > 1<<20 {
		return nil, errors.New("the embedded oc-gosys blob is missing")
	}
	c, ok := findCave(img, order, total)
	appended := false
	if !ok {
		// No gap is large enough: append the blob after the last segment. The
		// last segment becomes executable; this is only a fallback.
		last := order[len(order)-1]
		s := &img.Progs[last]
		v := alignUp(s.Vaddr+s.Memsz, 64)
		off := s.Off + (v - s.Vaddr)
		fits := true
		for _, i := range order {
			if end := img.Progs[i].Off + img.Progs[i].Filesz; end > off {
				fits = false
				break
			}
		}
		if fits {
			c = cave{seg: last, vaddr: v, off: off}
			ok = true
			appended = true
		}
	}
	if !ok {
		return nil, errors.New("no room after the load segments for the oc-gosys code")
	}

	rw := order[len(order)-1]
	rwSeg := &img.Progs[rw]
	if rwSeg.Flags&pfW == 0 || (!appended && rw == c.seg) {
		return nil, errors.New("the data segment cannot hold the scratch memory")
	}

	// The entry and the thunk stay together at the start of the blob.
	entryV := c.vaddr + uint64(gosysEntryOff)
	words, err := thunkWords(c.vaddr, entryV, siteV)
	if err != nil {
		return nil, err
	}
	toThunk, ok := branch(0x14000000, siteV, c.vaddr+blobThunkOff)
	if !ok {
		return nil, errors.New("the call site is out of branch range")
	}

	// The scratch area is zero-filled memory after the data segment (or after
	// the blob when the blob itself had to be appended to that segment).
	scratchV := alignUp(rwSeg.Vaddr+rwSeg.Memsz, 64)
	if appended {
		scratchV = alignUp(c.vaddr+total, 64)
	}

	// Extend the two program headers. The file is grown to the end of the blob.
	newFilesz := c.vaddr + total - img.Progs[c.seg].Vaddr
	newMemsz := scratchV - rwSeg.Vaddr + gosysScratchSize

	out := make([]byte, len(data))
	copy(out, data)
	if need := int(c.off + total); need > len(out) {
		out = append(out, make([]byte, need-len(out))...)
	}
	if appended {
		// The blob now lives after the bss of the last segment. The bytes
		// between the old end of the file-backed data and the blob used to be
		// zero-filled bss; now the segment maps them from the file, so they
		// have to be cleared or the globals of the program would start with the
		// old file content (the section headers, for example).
		from := int(rwSeg.Off + rwSeg.Filesz)
		to := int(c.off + total)
		if from < 0 || to < from || to > len(out) {
			return nil, errors.New("the appended blob is outside the file")
		}
		// Clearing a range that belongs to another segment would corrupt it.
		for _, i := range order {
			p := &img.Progs[i]
			if i == rw || p.Filesz == 0 {
				continue
			}
			if p.Off < uint64(to) && uint64(from) < p.Off+p.Filesz {
				return nil, errors.New("the bss of the data segment overlaps another segment")
			}
		}
		for i := from; i < to; i++ {
			out[i] = 0
		}
	}

	copy(out[c.off:c.off+total], gosysBlob)
	binary.LittleEndian.PutUint64(out[c.off+blobScratchRelOff:], scratchV-c.vaddr)

	thunk := out[c.off+blobThunkOff:]
	for i, w := range words {
		binary.LittleEndian.PutUint32(thunk[i*4:], w)
	}
	binary.LittleEndian.PutUint32(out[siteOff:], toThunk)

	caveSeg := &img.Progs[c.seg]
	binary.LittleEndian.PutUint32(out[caveSeg.Pos+4:], caveSeg.Flags|pfX)
	binary.LittleEndian.PutUint64(out[caveSeg.Pos+32:], newFilesz)
	binary.LittleEndian.PutUint64(out[caveSeg.Pos+40:], newFilesz)
	binary.LittleEndian.PutUint64(out[rwSeg.Pos+40:], newMemsz)

	return out, nil
}

// loadOrderByVaddr sorts the indices of the load segments by virtual address.
type loadOrderByVaddr struct {
	progs []programHeader
	order []int
}

func (o loadOrderByVaddr) Len() int { return len(o.order) }
func (o loadOrderByVaddr) Less(i, j int) bool {
	return o.progs[o.order[i]].Vaddr < o.progs[o.order[j]].Vaddr
}
func (o loadOrderByVaddr) Swap(i, j int) { o.order[i], o.order[j] = o.order[j], o.order[i] }

// patchFile writes a patched copy of [src] to [dst].
func patchFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	img, err := parseELF(data)
	if err != nil {
		return err
	}
	out, err := patchData(data, img)
	if err != nil {
		return err
	}

	dir := filepath.Dir(dst)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".oc-gopatch-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, err := tmp.Write(out); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	mode := os.FileMode(0o755)
	if st, err := os.Stat(src); err == nil {
		mode = st.Mode().Perm() | 0o111
	}
	if err := os.Chmod(name, mode); err != nil {
		return err
	}
	return os.Rename(name, dst)
}
