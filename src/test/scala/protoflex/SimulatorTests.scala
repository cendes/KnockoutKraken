package protoflex

import java.io.{BufferedInputStream, FileInputStream, FileOutputStream, IOException}
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

import chisel3._
import chisel3.iotesters
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}

import common.PROCESSOR_TYPES.REG_N

import utils.ArmflexJson
import utils.SoftwareStructs._

object FA_QflexCmds {
  // Commands SIM->QEMU
  val DATA_LOAD   = 0
  val DATA_STORE  = 1
  val INST_FETCH  = 2
  val INST_UNDEF  = 3
  val SIM_EXCP   = 4
  // Commands QEMU->SIM
  val SIM_START  = 5 // Load state from QEMU
  val SIM_STOP   = 6
  // Commands QEMU<->SIM
  val LOCK_WAIT   = 7
  val CHECK_N_STEP = 8
  def printCmd(cmd: Int)= {
    cmd match {
      case DATA_LOAD    => println("DATA_LOAD  ")
      case DATA_STORE   => println("DATA_STORE ")
      case INST_FETCH   => println("INST_FETCH ")
      case INST_UNDEF   => println("INST_UNDEF ")
      case SIM_EXCP     => println("SIM_EXCP   ")
      case SIM_START    => println("SIM_START  ")
      case SIM_STOP     => println("SIM_STOP   ")
      case LOCK_WAIT    => println("LOCK_WAIT  ")
      case CHECK_N_STEP => println("CHECK_N_STEP")
    }
  }
}

/* sim : Chisel3 simulation
 * qemu: QEMU    simulation
 */
case class SimulatorConfig(
  simStateFilename: String,
  simLockFilename: String,
  simCmdFilename : String,

  qemuStateFilename: String,
  qemuLockFilename: String,
  qemuCmdFilename: String,

  pageFilename:String,
  val pageSizeBytes:Int,
  val rootPath : String = "/dev/shm/qflex",
) {
  val simStatePath  = rootPath + "/" + simStateFilename
  val simLockPath   = rootPath + "/" + simLockFilename
  val simCmdPath    = rootPath + "/" + simCmdFilename
  val qemuStatePath = rootPath + "/" + qemuStateFilename
  val qemuLockPath  = rootPath + "/" + qemuLockFilename
  val qemuCmdPath   = rootPath + "/" + qemuCmdFilename
  val pagePath      = rootPath + "/" + pageFilename
}

class SimulatorTests(c_ : Proc, val cfg: SimulatorConfig)(implicit val procCfg: ProcConfig) extends PeekPokeTester(c_) with ProcTestsBase {
  val INSN_SIZE = 4
  val pageSize = cfg.pageSizeBytes/INSN_SIZE
  override val c = c_

  val program_page = Array.ofDim[Int](pageSize)

  def updateProgramPage(path: String) = {
    val bytearray: Array[Byte] = Files.readAllBytes(Paths.get(path))
    var hex = ""
    for(i <- 0 until pageSize) {
      val insn_LE = bytearray.slice(i*INSN_SIZE, i*INSN_SIZE+INSN_SIZE)
      program_page(i) = ByteBuffer.wrap(insn_LE.reverse).getInt
      // Write Instruction word to BRAM
      write_ppage(program_page(i), i)
      //hex += ByteBuffer.wrap(insn_LE).getInt.toHexString + "\n" // Disassemble for debug
    }
    //writeFile(path + "_dis", hex) // Disassemble for debug
  }

  def readFile(path: String):String= {
    val source = scala.io.Source.fromFile(path)
    val lines = try source.mkString finally source.close()
    lines
  }

  def writeFile(path: String, text: String): Unit = {
    Files.write(Paths.get(path), text.getBytes(StandardCharsets.UTF_8))
  }

  def writePState2File(path: String, pstate: PState): Unit = {
    val json = ArmflexJson.state2json(pstate)
    writeFile(path, json)
  }

  def updatePState(json: String, tag : Int):Unit = {
    val pstate: PState = ArmflexJson.json2state(json)
    write_pstate(tag,pstate)
  }

  var tf = System.nanoTime
  var ti = System.nanoTime
  var timeoutms = 10000

  def timedOut = {
    val isTimeOut = (tf - ti) / 1e6d > timeoutms
    //println("time_elapsed:" + (tf - ti) / 1e9d)
    if(isTimeOut) {
      println("TIMED OUT")
      backend.finish
      System.exit(1)
    }
    isTimeOut
  }

  def waitForCmd(filepath:String): Int = {
    println("WAITING FOR COMMAND")
    ti = System.nanoTime
    var cmd: Int = FA_QflexCmds.LOCK_WAIT
    do {
      Thread.sleep(500)
      cmd = ArmflexJson.json2cmd(readFile(filepath))._1
      tf = System.nanoTime
    } while(cmd == FA_QflexCmds.LOCK_WAIT && !timedOut);
    println(s"CONSUMED COMMAND : ${cmd}")
    writeCmd((FA_QflexCmds.LOCK_WAIT, 0), filepath) // Consume Command
    cmd
  }

  def writeCmd(cmd: (Int, Long), path: String) = {
    val json = ArmflexJson.cmd2json(cmd._1, cmd._2)
    println(s"Writing command in ${path}, json: ${json}")
    writeFile(path, json)
  }

  def run() = {
    println("RUN START")
    ti = System.nanoTime
    start_rtl()
    do {
      step(1)
      tf = System.nanoTime
    } while(peek(c.io.host2tpu.done) == 0 && !timedOut);
    step(10)
    println("RUN DONE")
  }


  def runStepping() = {
    println("RUN START")
    ti = System.nanoTime
    start_rtl()
    do {
      step(1)
      if(peek(c.io.procStateDBG.get.comited)) {
        val rtlPState = getPStateInternal(0)
        writePState2File(cfg.simStatePath, rtlPState)
        writeCmd((FA_QflexCmds.CHECK_N_STEP, 0), cfg.qemuCmdPath)
        waitForCmd(cfg.simCmdPath)
        val qemuPState = ArmflexJson.json2state(readFile(cfg.qemuStatePath))
        println("Comparing States: QEMU <-> RTL")
        qemuPState.compare(rtlPState)
      }
      tf = System.nanoTime
    } while(peek(c.io.host2tpu.done) == 0 && !timedOut);
    step(10)
    println("RUN DONE")
  }


  def runSimulator(timeoutms_i: Long):Unit = {
    ti = System.nanoTime
    tf = System.nanoTime
    timeoutms = timeoutms_i
    while(!timedOut) {
      val cmd = waitForCmd(cfg.simCmdPath)
      ti = System.nanoTime
      cmd match {
        case FA_QflexCmds.SIM_START =>
          println("SIMULATION START")
          updateProgramPage(cfg.pagePath)
          updatePState(readFile(cfg.qemuStatePath), 0)
          runStepping()
          writePState2File(cfg.simStatePath, read_pstate(0))
          writeCmd((FA_QflexCmds.INST_UNDEF, 0), cfg.qemuCmdPath)
        case FA_QflexCmds.SIM_STOP =>
          println("SIMULATION STOP")
          return
      }
     tf = System.nanoTime
    }
  }

  // Simulation routine
  runSimulator(50000)
}

class SimulatorTester(val cfg: SimulatorConfig) extends ChiselFlatSpec with ArmflexBaseFlatSpec {
  override val backends = Array("verilator")
  println("Starting Simulator")
  // Extra usefull args : --is-verbose
  val chiselArgs = Array("-tn", "proc", "-td","./test/Sim", "--backend-name", "verilator")
  iotesters.Driver.execute(chiselArgs, () => new Proc()) {
      c => new SimulatorTests(c, cfg)
  } should be(true)
  println("Done Simulator")
}

object SimulatorMain extends App {
  assert(args.length == 9)
  val cfg = new SimulatorConfig(
    args(0), args(1), args(2),
    args(3), args(4), args(5),
    args(6), args(7).toInt,
    args(8)
  )
  new SimulatorTester(cfg: SimulatorConfig)
}

