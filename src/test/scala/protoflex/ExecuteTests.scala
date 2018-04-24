package protoflex

import chisel3._
import chisel3.iotesters
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}

import utils.AssemblyParser
import utils.AssemblyInstruction
import common.DECODE_INTEGER_LITERALS._

class ExecuteALULog_SR(c: ExecuteUnit) extends PeekPokeTester(c)
{
  val instrs = AssemblyParser.parse("alu.x")
  val rVal1 = 1337
  val rVal2 = 79271

  val dinst_in = Seq(
    c.io.dinst.aluOp,
    c.io.dinst.rd,
    c.io.dinst.rs1,
    c.io.dinst.rs2,
    c.io.dinst.imm,
    c.io.dinst.shift,
    c.io.dinst.rd_en,
    c.io.dinst.rs1_en,
    c.io.dinst.rs2_en,
    c.io.dinst.imm_en,
    c.io.dinst.shift_en,
    c.io.dinst.inst_en
  )



  instrs foreach { inst =>
    println("Line : " + inst.line)
    dinst_in zip inst.io map { case (io, value) => poke(io, value)}
    poke(c.io.rVal1, rVal1)
    poke(c.io.rVal2, rVal2)

    if(inst.inst_en != 0)
    {
      //val val2 = if(inst.getSig("imm_en") == 0) rVal2 else inst.getSig("imm").toInt
      val val2 = if(inst.shift_en != N ) {
        (inst.shift.toInt, inst.imm.toInt) match {
          case (LSL, imm)  => rVal2 <<  imm
          case (LSR, imm)  => rVal2 >>> imm
          case (ASR, imm)  => rVal2 >>  imm
          case (ROR, imm)  => rVal2 >>> imm | rVal2 << ~imm
        }
      } else { rVal2 }

      val res = inst.aluOp.toInt match {
       case OP_AND => (rVal1  &  val2)
       case OP_BIC => (rVal1  & ~val2)
       case OP_ORR => (rVal1  |  val2)
       case OP_ORN => (rVal1  | ~val2)
       case OP_EOR => (rVal1  ^  val2)
       case OP_EON => (rVal1  ^ ~val2)
     }

      expect(c.io.einst.res, res)
    }
    step(1)
  }
}

class ExecuteTester extends ChiselFlatSpec
{
  behavior of "Executer"

  private val backendNames = Array("firrtl")

  for ( backend <- backendNames ) {
    it should s"execute ALU ops and shifts for dual register operations (with $backend)" in {
      Driver(() => new ExecuteUnit, backend)((c) => new ExecuteALULog_SR(c)) should be (true)
    }
  }
}
