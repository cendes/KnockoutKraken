package armflex.demander.peripheral

import chisel3._
import chisel3.util._

import armflex.demander.software_bundle

import armflex.cache.PseudoTreeLRUCore
import armflex.demander.software_bundle.PageTableItem


// The purpose of this module is:
// - Two scratchpads to buffer the PT Set.
// - One DMA to fetch one PT Set to specific buffer and moving back
// - Get the LRU Element
// - replace the LRU Element with given PTE.

/**
 * One Set in the Page Table. It should contains more than one PTEs.
 * 
 * @note the size of this bundle is 96 * entryNumber
 */ 
class PageTableSetPacket(
  val entryNumber: Int = 16
) extends Bundle {
  val tags = Vec(entryNumber, new software_bundle.PTTag)
  val ptes = Vec(entryNumber, new software_bundle.PTEntry)
  
  val valids = UInt(entryNumber.W)
  val lru_bits = UInt(entryNumber.W)

  override def cloneType: this.type = new PageTableSetPacket(entryNumber).asInstanceOf[this.type]
} 

class PageSetBufferWriteRequestPacket(
  entryNumber: Int = 16
) extends Bundle {
  val item = new PageTableItem
  val index = UInt(log2Ceil(entryNumber).W)

  override def cloneType: this.type = new PageSetBufferWriteRequestPacket(entryNumber).asInstanceOf[this.type]
}

class PageSetBufferLookupReplyPacket(
  entryNumber: Int = 16
) extends Bundle {
  val item = new PageTableItem
  val index = UInt(log2Ceil(entryNumber).W)
  val hit_v = Bool()

  override def cloneType: this.type = new PageSetBufferLookupReplyPacket(entryNumber).asInstanceOf[this.type]
}

/**
 * Page table set buffer and its attached logic.
 * 
 * @param t the Chisel type of the PT Set
 */ 
class PageTableSetBuffer(
  t: PageTableSetPacket,
) extends MultiIOModule {
  val dma_data_i = IO(Flipped(Decoupled(UInt(512.W))))
  val entryNumber = t.entryNumber
  val requestPacketNumber = (entryNumber / 16) * 3
  val buffer_r = Reg(Vec(requestPacketNumber, UInt(512.W)))

  // Load logic.
  val dma_cnt_r = RegInit(UInt(log2Ceil(requestPacketNumber).W), 0.U)
  val load_enabled_vi = IO(Input(Bool()))
  val load_vr = RegInit(Bool(), false.B)
  //load_vr := Mux(load_cnt_r === (requestPacketNumber - 1).U, false.B, load_vr)
  when(dma_cnt_r === (requestPacketNumber - 1).U && dma_data_i.fire()){
    load_vr := false.B
  }.elsewhen(load_enabled_vi){
    load_vr := true.B
  }
  // Bind updated_buffer to buffer_r
  val updated_buffer = WireInit(buffer_r)
  updated_buffer(dma_cnt_r) := dma_data_i.bits

  // Store logic.
  val dma_data_o = IO(Decoupled(UInt(512.W)))
  val store_enable_vi = IO(Input(Bool()))
  val store_vr = RegInit(Bool(), false.B)
  when(dma_cnt_r === (requestPacketNumber-1).U && dma_data_o.fire()){
    store_vr := false.B
  }.elsewhen(store_enable_vi){
    store_vr := true.B
  }

  // update of dma_cnt_r
  when(dma_data_i.fire() && load_vr || dma_data_o.fire() && store_vr){
    dma_cnt_r := Mux(
      dma_cnt_r === (requestPacketNumber - 1).U,
      0.U,
      dma_cnt_r + 1.U
    )
  }
  
  dma_data_o.valid := store_vr
  dma_data_o.bits := buffer_r(dma_cnt_r)

  val pt_set_r = buffer_r.asTypeOf(t.cloneType)
  // Get LRU Element (Maybe available element)
  val space_index = PriorityEncoder(~pt_set_r.valids)
  val u_lru_core = Module(new PseudoTreeLRUCore(t.entryNumber))
  u_lru_core.io.encoding_i := pt_set_r.lru_bits(t.entryNumber-2, 0)
  val lru_index = u_lru_core.io.lru_o
  val lru_item = Wire(new software_bundle.PageTableItem)
  lru_item.entry := pt_set_r.ptes(lru_index)
  lru_item.tag := pt_set_r.tags(lru_index)

  class get_lru_element_response_t extends Bundle {
    val item = new software_bundle.PageTableItem
    val lru_v = Bool()
    val index = UInt(log2Ceil(t.entryNumber).W)
    // That lru_v is true means the item is valid. 
    // False means this set is not full and there is a available place.
  }

  val lru_element_o = IO(Output(new get_lru_element_response_t))
  lru_element_o.item := lru_item
  lru_element_o.lru_v := ~pt_set_r.valids === 0.U
  lru_element_o.index := Mux(lru_element_o.lru_v, lru_index, space_index)

  // Update LRU Element
  val updated_pt_set = WireInit(pt_set_r)

  // val lru_element_i = IO(Flipped(Decoupled(new software_bundle.PageTableItem)))
  val write_request_i = IO(Flipped(Decoupled(new PageSetBufferWriteRequestPacket(t.entryNumber))))

  updated_pt_set.ptes(write_request_i.bits.index) := write_request_i.bits.item.entry
  updated_pt_set.tags(write_request_i.bits.index) := write_request_i.bits.item.tag

  u_lru_core.io.lru_i := write_request_i.bits.index
  updated_pt_set.lru_bits := u_lru_core.io.encoding_o
  updated_pt_set.valids := Cat(0.U(1.W), UIntToOH(write_request_i.bits.index))

  val lookup_request_i = IO(Input(new software_bundle.PTTag))
  val hit_vector = pt_set_r.tags.zip(pt_set_r.valids.asBools()).map({
    case (tag, valid) => 
    tag.process_id === lookup_request_i.process_id && 
    tag.vpn === lookup_request_i.vpn &&
    valid
  })

  val hit_v = VecInit(hit_vector).asUInt() =/= 0.U  
  assert(PopCount(hit_vector) === 1.U || PopCount(hit_vector) === 0.U, "There should be only one hit at most!!!")

  val hit_index = OHToUInt(hit_vector)
  val lookup_reply_o = IO(Output(new PageSetBufferLookupReplyPacket))
  lookup_reply_o.hit_v := hit_v
  lookup_reply_o.item.entry := pt_set_r.ptes(hit_index)
  lookup_reply_o.item.tag := lookup_request_i
  lookup_reply_o.index := hit_index

  when(write_request_i.valid){
    buffer_r := updated_pt_set.asTypeOf(buffer_r.cloneType)
  }.elsewhen(dma_data_i.fire()){
    buffer_r := updated_buffer
  }
  write_request_i.ready := true.B

  dma_data_i.ready := load_vr && !write_request_i.valid

}

