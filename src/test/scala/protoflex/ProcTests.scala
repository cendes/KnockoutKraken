package protoflex

import scala.collection.mutable

import java.io.{FileInputStream, FileOutputStream, IOException}
import java.nio.ByteBuffer

import java.math.BigInteger

import chisel3._
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester, PeekPokeTests}
import utils.AssemblyParser
import utils.SoftwareStructs
import common.PROCESSOR_TYPES.{DATA_SZ, NUM_THREADS, REG_N}


// base class contain utils for proc testing
trait ProcTestsBase extends PeekPokeTests {
  def int(x: Int): BigInt = { BigInt(x) }
  def int(x: Long): BigInt = { BigInt(x) }

  // These peek's are missingin PeekPokeTests trait,
  // but are present in PeekPokeTester
  def peek(signal: Aggregate): Seq[BigInt]
  def peek(signal: Bundle): mutable.LinkedHashMap[String, BigInt]

  val c: Proc
  val random = scala.util.Random

  def printState():Unit = {
    val sFet = SoftwareStructs.dinst(peek(c.io.inst_in))
    val sDec = if(peek(c.io.dec_reg.valid) == 1) SoftwareStructs.dinst(peek(c.io.dec_reg.bits)) else "XXX"
    val sIss = if(peek(c.io.issue_reg.valid) == 1) SoftwareStructs.dinst(peek(c.io.issue_reg.bits)) else "XXX"
    val sExe = if(peek(c.io.exe_reg.valid) == 1) SoftwareStructs.einst(peek(c.io.exe_reg.bits)) else "XXX"
    val sBr  = if(peek(c.io.br_reg.valid) == 1) SoftwareStructs.binst(peek(c.io.br_reg.bits)) else "XXX"
    val wbEn =  peek(c.io.wen)
    val state = Seq(
      "+----------------------------------------------------------------------------------+",
      "|                                   STATE                                          |",
      "+----------------------------------------------------------------------------------+",
      "-------------------------------- DECODE STAGE --------------------------------------",
      "Inst in : \n" + sFet,
      "-------------------------------- DECODE STAGE --------------------------------------",
      "Reg_Dec : \n" + sDec,
      "-------------------------------- ISSUE STAGE ---------------------------------------",
      "Reg_Iss : \n" + sIss,
      "------------------------------- EXECUTE STAGE --------------------------------------",
      "                    |      ",
      "                    |      ",
      "             +------------+",
      "             |            |",
      "  +---------Reg_Exe :     |",
      "  |                        \n" + sExe,
      "             |            |",
      "             |            |",
      "             |            |",
      "  +----------------------Reg_Br :",
      "  |                        \n" + sBr,
      "             |            |",
      "             |            |",
      "             |            |",
      "             |------------|",
      "                    |      ",
      "                    |      ",
      "------------------------------- WB STAGE -------------------------------------------",
      "                                                                               ",
      " PC : " + peek(c.io.curr_PC) + " -> " + peek(c.io.next_PC),
      "+----------------------------------------------------------------------------------+",
      "|                                   DONE                                           |",
      "+-----------------------------------------------------------------------------------\n",
    ).mkString("\n")
    print(state)
  }

  def printCycle(cycle: Int) = {
    val cycle_str = Seq(
      "+----------------------------------------------------------------------------------+",
      "|                                Cycle : "+cycle.toString.padTo(2,' ')+ "                                        |",
      "+-----------------------------------------------------------------------------------\n").mkString("\n")
    print(cycle_str)
  }

  // helper functions

  def getPState(tag: Int): SoftwareStructs.PState = {
    poke(c.io.tp_tag, tag)
    poke(c.io.tp_en, 1)
    step(1)
    val pc = peek(c.io.tp_pstate_in.PC).toLong
    val sp = peek(c.io.tp_pstate_in.SP).toLong
    val el = peek(c.io.tp_pstate_in.EL).toInt
    val nzcv = peek(c.io.tp_pstate_in.NZCV).toInt
    poke(c.io.tp_en, 0)
    val xregs = for (r <- 0 until REG_N) yield read_reg(tag)(r).toLong
    val pstate = PState(xregs.toList, pc, nzcv)
    pstate
  }


  def write_ppage(inst: Int, offst: Int) = {
    poke(c.io.ppage_bram.en, 1)
    poke(c.io.ppage_bram.writeEn.get, 1)
    poke(c.io.ppage_bram.addr, offst)
    poke(c.io.ppage_bram.dataIn.get, inst)
    step(1)
    poke(c.io.ppage_bram.en, 0)
    poke(c.io.ppage_bram.writeEn.get, 0)
  }

  def writeFullPPage(ppage: Array[Int], page_size: Int) = {
    for(i <- 0 to page_size) {
      write_ppage(ppage(i), i)
    }
  }

  def write32b_pstate(word: Int, offst: Int) = {
    poke(c.io.state_bram.en, 1)
    poke(c.io.state_bram.writeEn.get, 1)
    poke(c.io.state_bram.addr, offst)
    poke(c.io.state_bram.dataIn.get, word)
    step(1)
    poke(c.io.state_bram.en, 0)
    poke(c.io.state_bram.writeEn.get, 0)
  }

  // NOTE: Writes 2 words
  def write64b_pstate(lw: Long, offst: Int) = {
    val bytes = Array.fill(8)(0.toByte)
    for( i <- 0 to 7 ) bytes(i) = ((lw >> ((7-i) * 8)) & 0xFF).toByte
    val msb = ByteBuffer.wrap(bytes.slice(0, 4)).getInt
    val lsb = ByteBuffer.wrap(bytes.slice(4, 8)).getInt
    println("MSB:" + msb + "LSB:" + lsb)
    write32b_pstate(msb, offst)
    write32b_pstate(lsb, offst+1)
  }



  def write_pstate(tag: Int, pstate: SoftwareStructs.PState): Unit ={
    var offst = 0
    for(i <- 0 until 32 ) {
      write64b_pstate(pstate.xregs(i), offst); offst += 2
    }
    write64b_pstate(pstate.pc, offst: Int); offst+=2
    write32b_pstate(0, offst); offst+=1
    write32b_pstate(0, offst); offst+=1
    write32b_pstate(pstate.nzcv, offst); offst+=1
  }

  def read32b_pstate(offst:Int):Int = {
    poke(c.io.state_bram.en, 1)
    poke(c.io.state_bram.writeEn.get, 0)
    poke(c.io.state_bram.addr, offst)
    step(1)
    poke(c.io.state_bram.en, 0)
    poke(c.io.state_bram.writeEn.get, 0)
    val uint32 = peek(c.io.state_bram.dataOut.get)
    return uint32.toInt
  }

  def read64b_pstate(offst:Int):Long = {
    val msb = read32b_pstate(offst)
    val lsb = read32b_pstate(offst+1)
    val byte_msb = Array.fill(4)(0.toByte)
    val byte_lsb = Array.fill(4)(0.toByte)
    for (i <- 0 to 3) byte_msb(i) = ((msb >> ((3-i) * 8)) & 0xFF).toByte
    for (i <- 0 to 3) byte_lsb(i) = ((lsb >> ((3-i) * 8)) & 0xFF).toByte
    ByteBuffer.wrap((byte_msb ++ byte_lsb)).getLong
  }

  def read_pstate(tag: Int): SoftwareStructs.PState ={
    var offst = 0
    val xregs = for(i <- 0 until 32 ) yield  {
      val reg = read64b_pstate(offst)
      offst += 2
      reg
    }

    val pc = read64b_pstate(offst: Int); offst+=2
    val el = read32b_pstate(offst); offst+=1
    val sp = read32b_pstate(offst); offst+=1
    val nzcv = read32b_pstate(offst); offst+=1

    new SoftwareStructs.PState(xregs.toList: List[Long], pc: Long, nzcv: Int)
  }
}

// test basic pipline
class ProcTestsPipeline(c_ : Proc) extends PeekPokeTester(c_) with ProcTestsBase
{
  override val c = c_
  val insts = AssemblyParser.parse("alu.x")
  val br_insts = AssemblyParser.parse("branch.x")
  poke(c.io.br_reg.ready, 1)
  poke(c.io.exe_reg.ready, 1)
  poke(c.io.valid, 0)
  poke(c.io.mem_res.valid, 0)
  poke(c.io.mem_res.bits.data, 0)
  step(1)
  for(i <- 0 until 3) {
    val itype = random.nextInt(2)
    val inst = if (itype == 0) insts(random.nextInt(insts.size)) else br_insts(random.nextInt(br_insts.size))
    poke(c.io.inst, inst.bitPat)
    poke(c.io.tag, random.nextInt(4))
    poke(c.io.valid, 1)
    printCycle(i)
    printState()
    step(1)
  }

}

// test to run fixed number of instruction from page.x and write back states
abstract class ProcTestsPageBase(c_ : Proc) extends PeekPokeTester(c_) with ProcTestsBase
{
  override val c = c_
  val PAGE_SZ = 4096
  val rd_state_filename = "state"
  val rd_page_filename = "program_page" // page file in little endian
  val wr_filename = "write_back_state"
  // TODO: sanity check pstate, page file size

  // array hold the states
  val sp_reg_val = Array.ofDim[BigInt](NUM_THREADS, 4)
  val gn_reg_val = Array.ofDim[BigInt](NUM_THREADS, REG_N)
  val page = Array.ofDim[BigInt](PAGE_SZ/4)

  def write_state_file(): Unit = {
    def read_gen_reg(): Array[Array[BigInt]] ={
      val reg_val = Array.ofDim[BigInt](NUM_THREADS, REG_N)
      for(i <- 0 until NUM_THREADS){
        for (j <- 0 until REG_N) reg_val(i)(j) = read_reg(i)(j)
        println(reg_val(i).mkString(","))
      }
      reg_val
    }
    println("--- Write back states ---")
    def pad (in:Array[Byte], sz:Int) :Array[Byte] = {
      Array.fill[Byte](sz - in.size)(0)++ in
    }
    try{
      val wr_state = new FileOutputStream(wr_filename)
      val sp_reg = read_pstate()
      for( t <- sp_reg){
        for(i <- t) wr_state.write(pad(i.toByteArray, 8))
      }
      val gn_reg = read_gen_reg()
      for(t <- gn_reg){
        for (elem <- t) { wr_state.write(pad(elem.toByteArray, 8))}
      }
      wr_state.close()
    }catch{
      case e: IOException => println(s"file write error : ${e.toString}")
    }
  }

  // reading page file
  def read_page_file() ={
    val rd_page = new FileInputStream(rd_page_filename) // page file in little endian
    for(i <- 0 until PAGE_SZ/4){
      val bytes = Array.ofDim[Byte](5)
      rd_page.read(bytes,0,4)
      page(i) = BigInt(bytes.reverse)
    }
    rd_page.close()
  }

  // reading state file
  def read_state_file(): Unit ={
    val rd_state = new FileInputStream(rd_state_filename)
    def read_file(sz: Int) = {
      val bytes = Array.ofDim[Byte](sz)
      rd_state.read(bytes)
      BigInt(bytes)
    }
    for(i <- 0 until NUM_THREADS){
      sp_reg_val(i)(0) = read_file(DATA_SZ/8)
      sp_reg_val(i)(1) = read_file(DATA_SZ/8)
      sp_reg_val(i)(2) = read_file(DATA_SZ/8)
      sp_reg_val(i)(3) = read_file(DATA_SZ/8)
    }
    for(i<- 0 until NUM_THREADS; j <- 0 until REG_N){
      gn_reg_val(i)(j) = read_file(DATA_SZ/8)
    }
    rd_state.close()
  }

  // simulation starts
  // Initializing Proc
  def init(): Unit ={
    poke(c.io.flush, 0)
    for(t <- 0 until NUM_THREADS; r <- 0 until REG_N){
      write_reg(t)(r, gn_reg_val(t)(r))
    }
    for(i <- 0 until NUM_THREADS){
      write_pstate(i)(sp_reg_val(i)(0), sp_reg_val(i)(1), sp_reg_val(i)(2), sp_reg_val(i)(3))
    }
  }

  // execute instructions
  def run(start:Int, end:Int): Unit ={
    for(i <- start until end) {
      val inst = page(i)
      println(f"instruction ${inst}%x")
      poke(c.io.inst, inst)
      poke(c.io.tag, 0)
      poke(c.io.valid, 1)
      printCycle(i)
      printState()
      step(1)
    }
    printState()
  }

  def flush(): Unit ={
    val flush = Seq(
      "+----------------------------------------------------------------------------------+",
      "|                                   FLUSH                                          |",
      "+-----------------------------------------------------------------------------------\n",
    ).mkString("\n")
    println(flush)
    poke(c.io.flush, 1)
    printState()
    step(1)
    poke(c.io.flush, 0)
    println("flushing finished")
    step(1)
  }
}

class ProcTestsPage(c:Proc) extends ProcTestsPageBase(c){
  // these values are for the sample page file "programe_page"
  var start = 0x5c0/4
  var end = start + 10

  read_page_file()
  read_state_file()
  init()
  run(start,end)
  write_state_file()
}

class ProcTestsInterface(c:Proc) extends ProcTestsPageBase(c) {
  import scala.io.Source
  import java.io._

  // commands TODO: change to map
  def c_init = "INT"
  def c_ack = "ACK"
  def c_transplant = "TRN"
  def c_empty = ""

  val cmd_byte = Array.fill[Byte](3)(0)


  def write(cmd:String){
    val fw = new FileOutputStream("/tmp/qemu")
    fw.write(cmd.toCharArray.map(_.toByte))
    fw.close()
  }

  def read():String = {
    val fr = new FileInputStream("/tmp/armflex")
    fr.read(cmd_byte)
    fr.close()
    (cmd_byte.map(_.toChar)).mkString
  }

  var isDone = false
  while(!isDone){
    Thread.sleep(1000)
    println("listening to qemu ...")
    val cmd = read()
    println(s"$cmd")
    if (cmd == c_init){
      write(c_ack)
      println("initialize and start simualtion")
      read_page_file()
      read_state_file()
      init()
      // this is Current PC address of the sample pagefile
      var start = 0x5c0/4
      var end = start + 10
      run(start,end)
      write_state_file()
      isDone = true
    }
    else{

    }

  }
  Thread.sleep(1000)
  write(c_transplant)
  Thread.sleep(1000)
  write(c_empty)
}

class ProcTester extends ChiselFlatSpec
{
  behavior of "Proc"

  backends foreach { backend =>
    "ProcTestsReadyValid" should s"test Proc pipeline (with $backend)" in {
      Driver(() => new Proc()(new ProcConfig(0)), backend)((c) => new ProcTestsPipeline(c)) should be (true)
    }
  }
}

class ProcInitTester extends ChiselFlatSpec
{
  behavior of "Proc"

  backends foreach { backend =>
    "ProcTestsPage" should s"test Proc executing few instruction from page (with $backend)" in {
      Driver(() => new Proc()(new ProcConfig(0)), backend)((c) => new ProcTestsPage(c)) should be (true)
    }
  }
}

class ProcInterfaceTester extends ChiselFlatSpec
{
  behavior of "Proc"

  backends foreach { backend =>
    "ProcTestsPage" should s"test Proc executing few instruction from page (with $backend)" in {
      Driver(() => new Proc()(new ProcConfig(0)), backend)((c) => new ProcTestsInterface(c)) should be (true)
    }
  }
}
/**
  * This provides a way to ruin the firrtl-interpreter REPL (or shell)
  * on the lowered firrtl generated by your circuit. You will be placed
  * in an interactive shell. This can be very helpful as a debugging
  * technique. Type help to see a list of commands.
  */
