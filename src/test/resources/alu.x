
alu.o:     file format elf64-littleaarch64


Disassembly of section .text:

0000000000000000 <.text>:
   0:	8a0000af 	and	x15, x5, x0
   4:	8a20006c 	bic	x12, x3, x0
   8:	aa000070 	orr	x16, x3, x0
   c:	aa2002a1 	orn	x1, x21, x0
  10:	ca000165 	eor	x5, x11, x0
  14:	ca200021 	eon	x1, x1, x0
  18:	8a0f3d05 	and	x5, x8, x15, lsl #15
  1c:	8a543de3 	and	x3, x15, x20, lsr #15
  20:	8a800427 	and	x7, x1, x0, asr #1
  24:	8b1e00e5 	add	x5, x7, x30
  28:	cb000142 	sub	x2, x10, x0
