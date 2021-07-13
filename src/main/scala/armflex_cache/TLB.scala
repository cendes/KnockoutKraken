package armflex_cache

import armflex.{PTEntryPacket, PTTagPacket, PipeTLB}
import chisel3._
import chisel3.util._

case class TLBParameter(
  vPageWidth: Int = 52,
  pPageWidth: Int = 24,
  permissionWidth: Int = 2,
  asidWidth: Int = 15, // FIXME: This should appear here. But we have plan to merge TLBParam and Page Table param together.
  setNumber: Int = 1,
  associativity: Int = 32,
  threadNumber: Int = 32,
){
  def databankParameter: DatabankParameter = DatabankParameter(
    setNumber,
    associativity,
    pPageWidth + permissionWidth + 1, // PPN + permission + dirty
    vPageWidth,
    asidWidth,
    threadNumber
  )
}

/**
 * Frontend access (translate) request to TLB. 
 * @param param the TLB Parameter
 */ 
class TLBAccessRequestPacket(param: TLBParameter) extends Bundle {
  val tag = new PTTagPacket(param)
  val permission = UInt(2.W)
  val tid = UInt(log2Ceil(param.threadNumber).W)

  override def cloneType: this.type = new TLBAccessRequestPacket(param).asInstanceOf[this.type]

  def := (o: armflex.PipeTLB.PipeTLBRequest): Unit = {
    this.tag.asid := o.asid
    this.tag.vpn := o.addr >> 12 // page size
    this.permission := o.perm
    this.tid := o.thid
  }
}


/**
 * The translation result we get from the TLB.
 * @param param the TLB Parameter
 */ 

class TLBFrontendReplyPacket(param: TLBParameter) extends Bundle {
  // val pp = UInt(param.pPageWidth.W)
  // val modified = 
  val entry = new PTEntryPacket(param)
  val hit = Bool()
  val violation = Bool()
  // val dirty = Bool()
  val tid = UInt(log2Ceil(param.threadNumber).W)

  def toPipeTLBResponse: armflex.PipeTLB.PipeTLBResponse = {
    val res = Wire(new PipeTLB.PipeTLBResponse(param.pPageWidth))
    res.addr := entry.ppn << 12
    res.hit := hit
    res.miss := !res.hit
    res.permFault := violation
    res
  }

  override def cloneType: this.type = new TLBFrontendReplyPacket(param).asInstanceOf[this.type]
}

/**
 * Request TLB send to the backend for looking up the miss entry or writing back.
 * @param param the TLB Parameter
 * 
 */ 
class TLBBackendRequestPacket(param: TLBParameter) extends Bundle {
  val tag = new PTTagPacket(param)
  val entry = new PTEntryPacket(param)
  val w_v = Bool()
  val flush_v = Bool()
  val permission = UInt(2.W)
  val tid = UInt(log2Ceil(param.threadNumber).W)

  override def cloneType: this.type = new TLBBackendRequestPacket(param).asInstanceOf[this.type]

  def toAccessRequestPacket: TLBAccessRequestPacket = {
    val res = new TLBAccessRequestPacket(param)
    res.tag := this.tag
    res.permission := permission
    res.tid := this.tid
    res
  }
}

/**
 * The reply from the backend to a TLB. We assume that the backend is stateless.
 * @param param the TLB Parameter
 */ 
class TLBBackendReplyPacket(param: TLBParameter) extends Bundle {
  val tag = new PTTagPacket(param)
  val data = new PTEntryPacket(param)
  val tid = UInt(log2Ceil(param.threadNumber).W)

  override def cloneType: this.type = new TLBBackendReplyPacket(param).asInstanceOf[this.type]
}

class BRAMTLB(
  val param: TLBParameter,
  lruCore: () => LRUCore
) extends MultiIOModule {

  def tlbUpdateFunction(req: DataBankFrontendRequestPacket, oldEntry: CacheEntry): CacheEntry = {
    val oldTLBEntry = oldEntry.asTypeOf(new PTEntryPacket(param))
    Mux(
      oldTLBEntry.permissionValid(req.permission),
      oldEntry.write(req.wData, req.wMask, false.B, true.B),
      oldEntry
    )
  }

  val u_bank_frontend = Module(new DataBankManager(param.databankParameter, tlbUpdateFunction))
  val u_bram_adapter = Module(new BRAMPortAdapter(param.databankParameter))

  implicit val cfg = u_bram_adapter.bramCfg
  val bram = Module(new BRAMorRegister(false))
  u_bram_adapter.bram_ports(0) <> bram.portA
  u_bram_adapter.bram_ports(1) <> bram.portB

  u_bram_adapter.frontend_read_reply_data_o <> u_bank_frontend.bank_ram_reply_data_i
  u_bram_adapter.frontend_read_request_i <> u_bank_frontend.bank_ram_request_addr_o
  u_bram_adapter.frontend_write_request_i <> u_bank_frontend.bank_ram_write_request_o

  val u_lruCore = Module(new LRU(param.databankParameter, lruCore))
  u_lruCore.addr_i <> u_bank_frontend.lru_addr_o
  u_lruCore.index_i <> u_bank_frontend.lru_index_o
  u_bank_frontend.lru_which_i := u_lruCore.lru_o

  // frontend
  val frontend_request_i = IO(Flipped(Decoupled(new TLBAccessRequestPacket(param))))
  val frontend_reply_o = IO(Valid(new TLBFrontendReplyPacket(param)))
  // flush
  val flush_request_i = IO(Flipped(Decoupled(new PTTagPacket(param))))
  val flush_reply_o = IO(Valid(new TLBFrontendReplyPacket(param)))
  // backend
  val miss_request_o = IO(Decoupled(new TLBBackendRequestPacket(param)))
  val refill_request_i = IO(Flipped(Decoupled(new TLBBackendReplyPacket(param))))
  // activate
  val packet_arrive_o = IO(Valid(UInt(param.asidWidth.W)))

  val refill_request_internal = Wire(u_bank_frontend.frontend_request_i.cloneType)
  refill_request_internal.valid := refill_request_i.valid
  refill_request_internal.bits.addr := Cat(refill_request_i.bits.tag.asid, refill_request_i.bits.tag.vpn)
  refill_request_internal.bits.flush_v := false.B
  refill_request_internal.bits.tid := refill_request_i.bits.tid
  refill_request_internal.bits.asid := refill_request_i.bits.tag.asid
  refill_request_internal.bits.wData := DontCare
  refill_request_internal.bits.wMask := DontCare
  refill_request_internal.bits.refill_v := true.B
  refill_request_internal.bits.permission := DontCare
  refill_request_internal.bits.refillData := refill_request_i.bits.data.asUInt()

  refill_request_i.ready := refill_request_internal.ready

  packet_arrive_o.valid := refill_request_i.fire()
  packet_arrive_o.bits := refill_request_i.bits.tag.asid

  //val flush_ack_o = IO(Output(Bool()))

  val u_frontend_arb = Module(new Arbiter(u_bank_frontend.frontend_request_i.bits.cloneType(), 3))
  // the order:
  // - 0: Flush
  // - 1: Refill
  // - 2: Request

  // The normal request from the pipeline
  u_frontend_arb.io.in(2).valid := frontend_request_i.valid
  u_frontend_arb.io.in(2).bits.tid := frontend_request_i.bits.tid
  u_frontend_arb.io.in(2).bits.asid := frontend_request_i.bits.tag.asid
  u_frontend_arb.io.in(2).bits.addr := Cat(frontend_request_i.bits.tag.asid, frontend_request_i.bits.tag.vpn)
  val modified_pte = Wire(new PTEntryPacket(param))
  modified_pte.modified := true.B
  modified_pte.permission := 0.U
  modified_pte.ppn := 0.U
  u_frontend_arb.io.in(2).bits.wData := modified_pte.asUInt()
  u_frontend_arb.io.in(2).bits.flush_v := false.B
  u_frontend_arb.io.in(2).bits.wMask := Mux(frontend_request_i.bits.permission === 1.U, modified_pte.asUInt(), 0.U)
  u_frontend_arb.io.in(2).bits.permission := frontend_request_i.bits.permission
  u_frontend_arb.io.in(2).bits.refill_v := false.B
  u_frontend_arb.io.in(2).bits.refillData := DontCare

  frontend_request_i.ready := !flush_request_i.valid && !refill_request_i.valid

  // The flush request from other place
  u_frontend_arb.io.in(0).valid := flush_request_i.valid
  u_frontend_arb.io.in(0).bits.addr := Cat(frontend_request_i.bits.tag.asid, frontend_request_i.bits.tag.vpn)
  u_frontend_arb.io.in(0).bits.tid := DontCare
  u_frontend_arb.io.in(0).bits.asid := frontend_request_i.bits.tag.asid
  u_frontend_arb.io.in(0).bits.wData := DontCare
  u_frontend_arb.io.in(0).bits.flush_v := true.B
  u_frontend_arb.io.in(0).bits.wMask := DontCare
  u_frontend_arb.io.in(0).bits.permission := DontCare
  u_frontend_arb.io.in(0).bits.refill_v := false.B
  u_frontend_arb.io.in(0).bits.refillData := DontCare

  flush_request_i.ready := u_frontend_arb.io.in(0).ready
  // The refilling request from the backend of the cache
  u_frontend_arb.io.in(1) <> refill_request_internal

  u_bank_frontend.frontend_request_i.bits := u_frontend_arb.io.out.bits
  u_bank_frontend.frontend_request_i.valid := u_frontend_arb.io.out.valid
  u_frontend_arb.io.out.ready := u_bank_frontend.frontend_request_i.ready

  // convert the reply to PTE.
  val replied_pte = u_bank_frontend.frontend_reply_o.bits.rData.asTypeOf(new PTEntryPacket(param))

  // frontend_reply_o: Response to the R/W request from the pipeline
  frontend_reply_o.bits.hit := u_bank_frontend.frontend_reply_o.bits.hit
  // frontend_reply_o.bits.dirty := u_bank_frontend.frontend_reply_o.bits.dirty
  frontend_reply_o.bits.tid := u_bank_frontend.frontend_reply_o.bits.tid
  frontend_reply_o.bits.violation := !replied_pte.permissionValid(RegNext(frontend_request_i.bits.permission))
  frontend_reply_o.bits.entry := replied_pte

  frontend_reply_o.valid := u_bank_frontend.frontend_reply_o.valid && !u_bank_frontend.frontend_reply_o.bits.flush && !u_bank_frontend.frontend_reply_o.bits.refill
  // val packet_arrive_o = IO(u_bank_frontend.packet_arrive_o.cloneType)

  // flush_reply_o
  flush_reply_o.bits.hit := u_bank_frontend.frontend_reply_o.bits.hit
  flush_reply_o.bits.entry := replied_pte
  flush_reply_o.bits.tid := u_bank_frontend.frontend_reply_o.bits.tid
  // flush_reply_o.bits.dirty := u_bank_frontend.frontend_reply_o.bits.dirty
  flush_reply_o.bits.violation := false.B // Flush will never cause permission violation.
  flush_reply_o.valid := u_bank_frontend.frontend_reply_o.valid && u_bank_frontend.frontend_reply_o.bits.flush

  // miss request
  // val miss_request_o = IO(u_bank_frontend.miss_request_o.cloneType)
  miss_request_o.valid := u_bank_frontend.miss_request_o.valid
  miss_request_o.bits.tag.asid := u_bank_frontend.miss_request_o.bits.asid
  miss_request_o.bits.tag.vpn := u_bank_frontend.miss_request_o.bits.addr // only reserve the lower bits.
  miss_request_o.bits.permission := u_bank_frontend.miss_request_o.bits.permission
  miss_request_o.bits.tid := u_bank_frontend.miss_request_o.bits.tid
  miss_request_o.bits.flush_v := DontCare
  miss_request_o.bits.entry := DontCare
  miss_request_o.bits.w_v := u_bank_frontend.miss_request_o.bits.wMask =/= 0.U
  u_bank_frontend.miss_request_o.ready := miss_request_o.ready

  // write back request
  // So make the request pop all the time since we will not use it.
  u_bank_frontend.writeback_request_o.ready := true.B
}

