package armflex

import chisel3._
import chisel3.util._
import arm.PROCESSOR_TYPES._

import armflex.util._
import armflex.util.ExtraUtils._
import armflex.PipeTLB._

class FetchUnitPC(implicit val cfg: ProcConfig) extends MultiIOModule {
  val ctrl = IO(new Bundle {
    val start = Input(ValidTag(cfg.TAG_T, DATA_T))
    val commit = Input(ValidTag(cfg.TAG_T, DATA_T))
    val memWake = Input(Vec(2, ValidTag(cfg.TAG_T)))
  })

  val req = IO(DecoupledTag(cfg.TAG_T, DATA_T))

  val pc = RegInit(VecInit(Seq.fill(cfg.NB_THREADS)(DATA_X)))
  val en = RegInit(VecInit(Seq.fill(cfg.NB_THREADS)(false.B)))
  val rra = Module(new RoundRobinArbiter(cfg.NB_THREADS))

  rra.io.ready := en.asUInt
  req.handshake(rra.io.next)
  req.bits := pc(rra.io.next.bits)
  req.tag := rra.io.next.bits

  when(rra.io.next.fire) {
    en(rra.io.next.bits) := false.B
  }

  when(ctrl.start.valid) {
    // Wakeup from transplant
    pc(ctrl.start.tag) := ctrl.start.bits.get
    en(ctrl.start.tag) := true.B
  }
  when(ctrl.commit.valid) {
    // Wakeup from commited instruction
    pc(ctrl.commit.tag) := ctrl.commit.bits.get
    en(ctrl.commit.tag) := true.B
  }
  for(device <- 0 until 2) {
    when(ctrl.memWake(device).valid) {
    // Wakeup from Memory miss
      en(ctrl.memWake(device).tag) := true.B
    }
  }
}

// TODO: Expose event when instruciton is ditched (TLB miss/permError/etc)
class FetchUnit(
  cacheReqQ_entries: Int = 3,
  instQueue_entries: Int = 8
)(implicit val cfg: ProcConfig) extends MultiIOModule {

  // Generate PC
  private val pcUnit = Module(new FetchUnitPC)

  val ctrl_i = IO(pcUnit.ctrl.cloneType)
  val instQ_o = IO(Decoupled(new Tagged(cfg.TAG_T, INST_T)))
  val mem_io = IO(new PipeMemPortIO(DATA_SZ, cfg.pAddressWidth, cfg.NB_THREADS_W, cfg.ASID_WIDTH, cfg.BLOCK_SIZE))
  val mmu_io = IO(new PipeMMUPortIO)

  // ------- Modules
  // Get instruction blocks
  private val maxInstsInFlight = instQueue_entries
  private val cacheReqQ = Module(new Queue(new PendingCacheReq, cacheReqQ_entries, true, false))
  private val cacheAdaptor = Module(new PipeCache.CacheInterface(new MetaData, maxInstsInFlight, cfg.pAddressWidth, cfg.BLOCK_SIZE))
  // Actual instructions
  private val instQueue = Module(new Queue(new Tagged(cfg.TAG_T, INST_T), instQueue_entries, true, false))
  // Make sure receiver has enough entries left
  private val pc2cache_credits = Module(new CreditQueueController(cacheReqQ_entries))
  private val cache2insts_credits = Module(new CreditQueueController(instQueue_entries))

  pcUnit.ctrl <> ctrl_i

  // --------- TLB Request ---------------

  private val tlbDropInst = WireInit(false.B)


  // Data
  mem_io.tlb.req.bits.addr := pcUnit.req.bits
  mem_io.tlb.req.bits.thid := pcUnit.req.tag
  mem_io.tlb.req.bits.asid := DontCare // Higher in the hierarchy is the mapped ASID
  mem_io.tlb.req.bits.perm := 2.U // instruction permission

  class MetaData extends Bundle {
    val pc = Output(DATA_T)
    val id = Output(cfg.TAG_T)
  }
  private val metaDataTLB_w = Wire(new MetaData)
  metaDataTLB_w.pc := pcUnit.req.bits
  metaDataTLB_w.id := pcUnit.req.tag
  private val metaDataTLB_r = RegEnable(metaDataTLB_w, mem_io.tlb.req.fire)
  cacheReqQ.io.enq.bits.paddr := mem_io.tlb.resp.bits.addr
  cacheReqQ.io.enq.bits.meta := metaDataTLB_r


  // Handshakes management
  mem_io.tlb.req.valid := false.B
  pcUnit.req.ready := false.B
  mem_io.tlb.resp.ready := false.B
  cacheReqQ.io.enq.valid := false.B
  mem_io.tlb.req.handshake(pcUnit.req, pc2cache_credits.ready)
  when(mem_io.tlb.resp.bits.hit) {
    cacheReqQ.io.enq.handshake(mem_io.tlb.resp)
  }

  // Backpressure
  pc2cache_credits.trans.in := pcUnit.req.fire
  pc2cache_credits.trans.out := cacheReqQ.io.deq.fire
  pc2cache_credits.trans.dropped := mem_io.tlb.resp.fire && !mem_io.tlb.resp.bits.hit

  // ------- Cache Request, paddr available ---------

  // In case Cache is unavailable, store TLB requests
  class PendingCacheReq extends Bundle {
    val paddr = Output(mem_io.tlb.resp.bits.addr.cloneType)
    val meta = Output(new MetaData)
  }
  cacheAdaptor.pipe_io.req.meta := cacheReqQ.io.deq.bits.meta
  cacheAdaptor.pipe_io.req.port.bits.addr := cacheReqQ.io.deq.bits.paddr
  cacheAdaptor.pipe_io.req.port.bits.data := DontCare
  cacheAdaptor.pipe_io.req.port.bits.w_en := false.B
  mem_io.cache <> cacheAdaptor.cache_io


  // Get 32 bit Instruction from response block
  private val respMetaData = WireInit(cacheAdaptor.pipe_io.resp.meta)
  private val dataBlock = WireInit(cacheAdaptor.pipe_io.resp.port.bits.data)
  private val selectBlock = WireInit(respMetaData.pc(log2Ceil(cfg.BLOCK_SIZE/8) - 1, 0) >> 2.U)
  private val blockInsts = VecInit.tabulate(cfg.BLOCK_SIZE/INST_SZ) {
    idx => dataBlock((idx + 1) * 32 - 1, idx * 32)
  }
  private val instBits = WireInit(blockInsts(selectBlock.asUInt))

  // Push 32 bit instruction to queue
  instQueue.io.enq.bits := Tagged(respMetaData.id, instBits)

  // Handshakes
  cacheAdaptor.pipe_io.req.port.valid := false.B
  cacheReqQ.io.deq.ready := false.B
  cacheAdaptor.pipe_io.resp.port.ready := false.B
  instQueue.io.enq.valid := false.B
  cacheAdaptor.pipe_io.req.port.handshake(cacheReqQ.io.deq, cache2insts_credits.ready)
  instQueue.io.enq.handshake(cacheAdaptor.pipe_io.resp.port)

  // Backpressure
  cache2insts_credits.trans.in := cacheReqQ.io.deq.fire
  cache2insts_credits.trans.out := instQueue.io.deq.fire
  cache2insts_credits.trans.dropped := false.B

  // Next stage
  instQ_o <> instQueue.io.deq

  // -----------------------------------------
  // -------------- Flush Request ------------
  // -----------------------------------------
  private val flushController = Module(new PipeCache.CacheFlushingController)
  private val haveCacheReq = WireInit(cacheReqQ.io.deq.valid)
  private val havePendingCacheReq = WireInit(cacheAdaptor.pending =/= 0.U)
  when(flushController.ctrl.stopTransactions){
    pcUnit.req.ready := false.B
    mem_io.tlb.req.valid := false.B
  }

  flushController.ctrl.hasPendingWork := haveCacheReq || havePendingCacheReq

  mmu_io <> flushController.mmu_io

  if (true) { // TODO, conditional asserts
    // --- TLB Stage ---
    when(mem_io.tlb.resp.fire) {
      assert(RegNext(mem_io.tlb.req.fire), "Hit response of TLB should arrive in 1 cycle")
    }

    when(!pc2cache_credits.ready) {
      assert(!pcUnit.req.fire, "Can't fire requests if not enough credits left")
    }
    when(!cache2insts_credits.ready) {
      assert(!cacheAdaptor.pipe_io.req.port.fire, "Can't fire new request if instruction queue doesn't have enough credits")
    }
    when(cacheReqQ.io.enq.valid) {
        assert(cacheReqQ.io.enq.ready, "Credit system should ensure that receiver has always enough entries left")
    }
    when(instQueue.io.enq.valid) {
      assert(instQueue.io.enq.ready, "Credit system should ensure that receiver has always enough entries left")
    }
    when(mem_io.cache.resp.valid) {
      assert(cacheAdaptor.pipe_io.resp.port.valid, "Cache Adaptor only acts as a module to forward meta data and has no latency")
      assert(instQueue.io.enq.ready, "Credit system should ensure that receiver has always enough entries left")
    }
    when(mem_io.cache.req.valid) {
      assert(cacheAdaptor.pipe_io.req.port.valid, "Cache Adaptor only acts as a module to forward meta data and has no latency")
      assert(cacheReqQ.io.deq.valid, "Cache requests must come from the queue")
    }
    when(cacheAdaptor.pipe_io.resp.port.fire) {
      assert(instQueue.io.enq.fire, "On normal access, instruction must be pushed to completed insts")
    }

    // --- Flush assertions ---
    when(flushController.ctrl.stopTransactions) {
      assert(!mem_io.tlb.req.fire, "A new translation should never be sent once starting to fill flushing permissions")
    }
    when(flushController.ctrl.waitingForMMU) {
      assert(!haveCacheReq && !havePendingCacheReq, "No new cache requests should ever appear given that we stopped translating")
    }
  }
}