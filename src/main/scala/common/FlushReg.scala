package common

import chisel3._
import chisel3.util.{log2Ceil, Counter, Decoupled, ReadyValidIO, IrrevocableIO, Valid}
import chisel3.experimental.{DataMirror, Direction, requireIsChiselType}

/** An I/O Bundle for FlushReg (FlushRegister)
  * @param gen The type of data of the Reg
  */
class FlushRegIO[T <: Data](private val gen: T) extends Bundle
{
  val enq = Flipped(Decoupled(gen))
  val deq = Decoupled(gen)
  /** Flush */
  val flush = Input(Bool())
}

class FlushReg[T <: Data](gen: T)
    extends Module() {

  val io = IO(new FlushRegIO(gen))

  private val reg = Reg(gen)
  private val valid = RegInit(false.B)

  private val do_enq = WireInit(!valid || io.deq.ready || io.flush)

  when (do_enq) {
    reg := io.enq.bits
    valid := io.enq.valid
  }
  io.enq.ready := do_enq

  io.deq.bits := reg
  io.deq.valid := valid && !io.flush

}

class FlushQueue[T <: Data](gen: T, entries: Int = 2)
    extends Module() {

  val io = IO(new FlushRegIO(gen))

  private val ram = Mem(entries, gen)
  private val enq_ptr = RegInit(0.U(log2Ceil(entries).W))
  private val deq_ptr = RegInit(0.U(log2Ceil(entries).W))
  private val maybe_full = RegInit(false.B)

  private val ptr_match = WireInit(enq_ptr === deq_ptr)
  private val empty = WireInit(ptr_match && !maybe_full)
  private val full = WireInit(ptr_match && maybe_full)

  private val do_enq = WireInit(io.enq.valid && !full)
  private val do_deq = WireInit(io.deq.ready && !empty)

  when (do_enq) {
    ram(enq_ptr) := io.enq.bits
    enq_ptr := enq_ptr + 1.U
  }
  when (do_deq) {
    deq_ptr := deq_ptr + 1.U
  }

  when(do_enq =/= do_deq) {
    maybe_full := do_enq
  }

  io.enq.ready := !full || io.deq.ready
  io.deq.valid := !empty && !io.flush
  io.deq.bits := ram(do_deq)

  when(io.flush) {
    enq_ptr := 0.U
    deq_ptr := 0.U
    maybe_full := false.B

    io.deq.valid := false.B
    io.enq.ready := true.B
  }
}

object FlushQueue {
  def apply[T <: Data](genTag: T, entries: Int): FlushQueue[T] = new FlushQueue(genTag, entries)
}
