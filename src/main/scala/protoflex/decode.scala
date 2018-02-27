package protoflex

import chisel3._
import chisel3.util.{BitPat, ListLookup, MuxLookup, Cat}

import common.SIGNALS._
import common.INSTRUCTIONS._

class DInst extends Bundle
{
  // Data
  val rd    = REG_T
  val rs1   = REG_T
  val rs2   = REG_T
  val imm   = IMM_T
  val shift = SHIFT_T

  // Control
  val imm_valid = Bool()
  val aluOp = OP_ALU_T
  val shift_v = Bool()
  val valid = Bool()

  def decode(inst : UInt) = {
    val local_default = Decode.decode_control_default
    val local_table = Decode.table_control
    //val decoder = ListLookup(inst, Decode.decode_control_default, Decode.table_control)
    val decoder = ListLookup(inst, local_default, local_table)
    printf(p"$decoder")

    // Data
    val dtype = decoder.head
    rd    := MuxLookup(dtype,   REG_X, Array( I_LogSR -> inst(4,0) ))
    rs1   := MuxLookup(dtype,   REG_X, Array( I_LogSR -> inst(9,5) ))
    rs2   := MuxLookup(dtype,   REG_X, Array( I_LogSR -> inst(20,16) ))
    imm   := MuxLookup(dtype,   IMM_X, Array( I_LogSR -> Cat(0.U(20.W), inst(15,10))))
    shift := MuxLookup(dtype, SHIFT_X, Array( I_LogSR -> inst(23,22) ))

    // Control
    val cdecoder = decoder.tail
    val csignals = Seq(imm_valid, aluOp, shift_v, valid)
    csignals zip cdecoder map { case (s, d) => s:= d }

    this
  }
}

object Decode
{
  def decode_control_default: List[UInt] =
    //
    //              ALU   INSTRUCTION
    // INSTR         OP     VALID
    // TYPE          |        |
    //   |   IMM     |   SHIFT|
    //   |  VALID    |   VALID|
    //   |   ALU     |     |  |
    //   |    |      |     |  |
    List(I_X, N, OP_ALU_X, N, N)

  def table_control: Array[(BitPat, List[UInt])]  =
    Array(
      /* Logical (shifted register) 64-bit */
      AND  -> List(I_LogSR, N, OP_AND, Y, Y),
      BIC  -> List(I_LogSR, N, OP_BIC, Y, Y),
      ORR  -> List(I_LogSR, N, OP_ORR, Y, Y),
      ORN  -> List(I_LogSR, N, OP_ORN, Y, Y),
      EOR  -> List(I_LogSR, N, OP_EOR, Y, Y),
      EON  -> List(I_LogSR, N, OP_EON, Y, Y)
    )
}

class DecodeUnitIO extends Bundle
{
  val inst = Input(UInt(32.W))
  val dinst = Output(new DInst)
}

class DecodeUnit extends Module
{
  val io = IO(new DecodeUnitIO)

  val dinst = Wire(new DInst).decode(io.inst)
  printf(p"new Wire")

  /*
  // Decode
  val local_default = Decode.decode_control_default
  val local_table = Decode.table_control
  //val decoder = ListLookup(inst, Decode.decode_control_default, Decode.table_control)
  val decoder = ListLookup(io.inst, local_default, local_table)
  printf(p"$decoder")

  // Data
  val dtype = decoder.head
  dinst.rd   := MuxLookup(dtype,   REG_X, Array( I_LogSR -> io.inst(4,0) ))
  dinst.rs1   := MuxLookup(dtype,   REG_X, Array( I_LogSR -> io.inst(4,0) ))
  dinst.rs2   := MuxLookup(dtype,   REG_X, Array( I_LogSR -> io.inst(4,0) ))
  dinst.imm   := MuxLookup(dtype,   IMM_X, Array( I_LogSR -> Cat(0.U(20.W), io.inst(15,10))))
  dinst.shift := MuxLookup(dtype, SHIFT_X, Array( I_LogSR -> io.inst(23,22) ))

  // Control
  val cdecoder = decoder.tail
  val csignals = Seq(dinst.imm_valid, dinst.aluOp, dinst.shift_v, dinst.valid)
  csignals zip cdecoder map { case (s, d) => s:= d }
  */

  io.dinst := dinst
}