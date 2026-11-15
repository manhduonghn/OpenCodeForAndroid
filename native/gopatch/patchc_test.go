package main

import (
	"encoding/binary"
	"testing"
)

func insnWords(ws ...uint32) []byte {
	out := make([]byte, len(ws)*4)
	for i, w := range ws {
		binary.LittleEndian.PutUint32(out[i*4:], w)
	}
	return out
}

func TestDecodeSyscallNr(t *testing.T) {
	const (
		movX8_220 = 0xd2801b88 // mov x8, #0xdc
		movW8_56  = 0x52800708 // movz w8, #0x38
		movX8X6   = 0xaa0603e8 // mov x8, x6
		branch    = 0x14000000 // b
	)
	cases := []struct {
		name string
		data []byte
		i    uint64
		want int
	}{
		{"movz x8", insnWords(movX8_220, svcInsn), 1, 220},
		{"movz w8", insnWords(movW8_56, svcInsn), 1, 56},
		{"dynamic", insnWords(movX8X6, svcInsn), 1, -1},
		{"no movz", insnWords(svcInsn, svcInsn), 1, -1},
		{"branch stops the scan", insnWords(movW8_56, branch, svcInsn), 2, -1},
		{"nearest movz wins", insnWords(branch, movW8_56, svcInsn), 2, 56},
	}
	for _, c := range cases {
		if got := decodeSyscallNr(c.data, 0, c.i); got != c.want {
			t.Errorf("%s: decodeSyscallNr = %d, want %d", c.name, got, c.want)
		}
	}
}

func TestIsBarrier(t *testing.T) {
	barriers := map[string]uint32{
		"b":       0x14000000,
		"bl":      0x94000000,
		"ret":     0xd65f03c0,
		"br":      0xd61f0000,
		"blr":     0xd63f0000,
		"svc":     svcInsn,
		"bti":     0xd503241f,
		"paciasp": 0xd503233f,
		"autiasp": 0xd50323bf,
	}
	for name, w := range barriers {
		if !isBarrier(w) {
			t.Errorf("%s (%#x) should be a barrier", name, w)
		}
	}
	plain := []uint32{0xaa0103e0, 0xd2801b88, 0xf94007fe} // mov, movz, ldr
	for _, w := range plain {
		if isBarrier(w) {
			t.Errorf("%#x should not be a barrier", w)
		}
	}
}

func TestPatchAutoRefusesPatchedFile(t *testing.T) {
	// patchAuto checks the magic before it looks at the image, so a nil image
	// is enough to prove the guard fires.
	if _, err := patchAuto([]byte("\x7fELF....OCGOSYS1...."), nil); err == nil {
		t.Fatal("patching a file that already carries the blob succeeded")
	}
}
