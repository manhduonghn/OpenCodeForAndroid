package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
)

const (
	elfMagic      = "\x7fELF"
	elfClass64    = 2
	elfDataLSB    = 1
	emAArch64     = 183
	emX86_64      = 62
	ptLoad        = 1
	ptInterp      = 3
	pfX           = 1
	pfW           = 2
	elfHeaderSize = 64
	phdrSize      = 56
)

type programHeader struct {
	Type   uint32
	Flags  uint32
	Off    uint64
	Vaddr  uint64
	Filesz uint64
	Memsz  uint64
	Align  uint64
	Pos    int // file offset of this program header entry
}

type elfImage struct {
	Data       []byte
	EType      uint16
	Machine    uint16
	Phoff      uint64
	Phentsize  uint16
	Phnum      uint16
	Progs      []programHeader
	Interp     string
	hasProgram bool
}

func readU16(b []byte, off int) uint16 { return binary.LittleEndian.Uint16(b[off:]) }
func readU32(b []byte, off int) uint32 { return binary.LittleEndian.Uint32(b[off:]) }
func readU64(b []byte, off int) uint64 { return binary.LittleEndian.Uint64(b[off:]) }

func writeU32(b []byte, off int, v uint32) { binary.LittleEndian.PutUint32(b[off:], v) }
func writeU64(b []byte, off int, v uint64) { binary.LittleEndian.PutUint64(b[off:], v) }

func parseELF(data []byte) (*elfImage, error) {
	if len(data) < elfHeaderSize || string(data[:4]) != elfMagic {
		return nil, errors.New("not an ELF file")
	}
	if data[4] != elfClass64 || data[5] != elfDataLSB {
		return nil, errors.New("not a 64-bit little-endian ELF")
	}
	img := &elfImage{
		Data:      data,
		EType:     readU16(data, 16),
		Machine:   readU16(data, 18),
		Phoff:     readU64(data, 32),
		Phentsize: readU16(data, 54),
		Phnum:     readU16(data, 56),
	}
	if img.Phentsize != phdrSize || img.Phnum == 0 || img.Phnum > 128 {
		return nil, fmt.Errorf("unexpected program header table (%d entries of %d bytes)", img.Phnum, img.Phentsize)
	}
	if img.Phoff > uint64(len(data)) || img.Phoff+uint64(img.Phnum)*phdrSize > uint64(len(data)) {
		return nil, errors.New("truncated program header table")
	}
	img.hasProgram = true
	for i := 0; i < int(img.Phnum); i++ {
		pos := int(img.Phoff) + i*phdrSize
		p := programHeader{
			Type:   readU32(data, pos),
			Flags:  readU32(data, pos+4),
			Off:    readU64(data, pos+8),
			Vaddr:  readU64(data, pos+16),
			Filesz: readU64(data, pos+32),
			Memsz:  readU64(data, pos+40),
			Align:  readU64(data, pos+48),
			Pos:    pos,
		}
		if p.Off+p.Filesz > uint64(len(data)) && p.Type == ptLoad {
			return nil, fmt.Errorf("segment %d extends past the end of the file", i)
		}
		img.Progs = append(img.Progs, p)
		if p.Type == ptInterp {
			if p.Off+p.Filesz > uint64(len(data)) {
				return nil, errors.New("truncated PT_INTERP")
			}
			value := data[p.Off : p.Off+p.Filesz]
			if n := strings.IndexByte(string(value), 0); n >= 0 {
				value = value[:n]
			}
			img.Interp = string(value)
		}
	}
	return img, nil
}

func parseELFHeader(r io.ReaderAt, size int64) (*elfImage, error) {
	header := make([]byte, elfHeaderSize)
	if _, err := r.ReadAt(header, 0); err != nil {
		return nil, err
	}
	if string(header[:4]) != elfMagic {
		return nil, errors.New("not an ELF file")
	}
	if header[4] != elfClass64 || header[5] != elfDataLSB {
		return nil, errors.New("not a 64-bit little-endian ELF")
	}
	phoff := readU64(header, 32)
	phentsize := readU16(header, 54)
	phnum := readU16(header, 56)
	if phentsize != phdrSize || phnum == 0 || phnum > 128 {
		return nil, fmt.Errorf("unexpected program header table (%d entries of %d bytes)", phnum, phentsize)
	}
	if phoff > uint64(size) || phoff+uint64(phnum)*phdrSize > uint64(size) {
		return nil, errors.New("truncated program header table")
	}
	table := make([]byte, int(phnum)*phdrSize)
	if _, err := r.ReadAt(table, int64(phoff)); err != nil {
		return nil, err
	}
	img := &elfImage{
		EType:     readU16(header, 16),
		Machine:   readU16(header, 18),
		Phoff:     phoff,
		Phentsize: phentsize,
		Phnum:     phnum,
	}
	img.hasProgram = true
	for i := 0; i < int(phnum); i++ {
		pos := i * phdrSize
		p := programHeader{
			Type:   readU32(table, pos),
			Flags:  readU32(table, pos+4),
			Off:    readU64(table, pos+8),
			Vaddr:  readU64(table, pos+16),
			Filesz: readU64(table, pos+32),
			Memsz:  readU64(table, pos+40),
			Align:  readU64(table, pos+48),
			Pos:    int(phoff) + pos,
		}
		img.Progs = append(img.Progs, p)
	}
	for _, p := range img.Progs {
		if p.Type != ptInterp {
			continue
		}
		if p.Off+p.Filesz > uint64(size) {
			return nil, errors.New("truncated PT_INTERP")
		}
		value := make([]byte, p.Filesz)
		if _, err := r.ReadAt(value, int64(p.Off)); err != nil {
			return nil, err
		}
		if n := strings.IndexByte(string(value), 0); n >= 0 {
			value = value[:n]
		}
		img.Interp = string(value)
	}
	return img, nil
}

// classifyFile returns one of: glibc, bionic, dynamic, go, static, script,
// foreign, unknown.
func classifyFile(path string) (string, string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", "", err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return "", "", err
	}
	head := make([]byte, 4)
	if n, _ := f.ReadAt(head, 0); n < 4 {
		return "unknown", "", nil
	}
	if string(head[:2]) == "#!" {
		line := make([]byte, 256)
		n, _ := f.ReadAt(line, 0)
		if i := strings.IndexByte(string(line[:n]), '\n'); i >= 0 {
			line = line[:i]
		}
		return "script", strings.TrimSpace(string(line[2:])), nil
	}
	img, err := parseELFHeader(f, st.Size())
	if err != nil {
		return "unknown", "", nil
	}
	if img.Machine != emAArch64 && img.Machine != emX86_64 {
		return "foreign", fmt.Sprintf("machine-%d", img.Machine), nil
	}
	if img.Interp != "" {
		switch {
		case strings.Contains(img.Interp, "ld-linux") || strings.Contains(img.Interp, "ld.so"):
			return "glibc", img.Interp, nil
		case strings.Contains(img.Interp, "linker"):
			return "bionic", img.Interp, nil
		default:
			return "dynamic", img.Interp, nil
		}
	}
	// Static: only an AArch64 Go program can be patched. For x86_64 the site
	// pattern is different and Android devices of that ABI are rare.
	if img.Machine == emAArch64 {
		if data, err := os.ReadFile(path); err == nil {
			if _, _, err := findGoSite(data, img); err == nil {
				return "go", "", nil
			}
		}
	}
	return "static", "", nil
}

func fileExists(path string) bool {
	st, err := os.Stat(path)
	return err == nil && !st.IsDir()
}
