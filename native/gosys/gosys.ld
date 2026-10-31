/* Flat layout of the code that is copied into a Go program.
 *
 *   0x00  struct oc_gosys_hdr (filled in by the patcher)
 *   0x20  reserved for the thunk that connects the call site (filled by the patcher)
 *   0x40  oc_stub_entry and the rest of the code
 *
 * The blob is linked at address 0. The patcher only places it at a page-aligned
 * virtual address, so every PC-relative reference (adrp/adr/literal) remains
 * valid when the blob is copied to the cave.
 */
OUTPUT_FORMAT(elf64-littleaarch64)
ENTRY(oc_stub_entry)
SECTIONS
{
  . = 0;
  .hdr : { KEEP(*(.hdr)) }
  . = 0x20;
  .thunk : { . += 0x20; }
  . = 0x40;
  .text : { KEEP(*(.text.oc_stub_entry)) *(.text .text.*) }
  .rodata : { . = ALIGN(16); *(.rodata .rodata.*) }
  /DISCARD/ : {
    *(.comment)
    *(.note*)
    *(.eh_frame*)
    *(.gnu*)
    *(.interp)
  }
}
