package armflex.util

import chisel3._
import chisel3.util._

import DMAController.Bus._
import armflex.demander.software_bundle.ParameterConstants

class AXIControlledMessageQueue extends MultiIOModule {
  val fifo_i = IO(Flipped(Decoupled(UInt(512.W))))
  val fifo_o = IO(Decoupled(UInt(512.W)))

  /**
   *  Address | Meaning
   *  0x0     | out queue is not full (can send)
   *  0x4     | in queue is not empty (can read message)
   */ 
  // TODO: add interrupt here when there is a message available
  val S_AXIL = IO(AxiLiteSlave(new AxiLiteConfig(13)))
  // write signals are not included.
  S_AXIL.awready := false.B
  S_AXIL.wready := false.B
  S_AXIL.bresp := 0.U
  S_AXIL.bvalid := false.B

  val addr_r = RegInit(0.U(3.W))
  val addr_vr = RegInit(false.B)
  
  when(S_AXIL.arready && S_AXIL.arvalid){
    addr_r := S_AXIL.araddr(2, 0)
    addr_vr := true.B
  }.elsewhen(S_AXIL.rready && S_AXIL.rvalid){
    addr_vr := false.B
  }

  S_AXIL.arready := !addr_vr
  
  S_AXIL.rdata := Mux(addr_r(2), fifo_i.valid, fifo_o.ready)
  S_AXIL.rresp := 0.U
  S_AXIL.rvalid := addr_vr

  // Read from any address will trigger a pop
  // write to any address will trigger a insersion.
  val S_AXI = IO(Flipped(
    new AXI4(
      7, 512
    )
  ))

  val u_axi_trans_converter = Module(new AXIRAMController(7, 512))
  u_axi_trans_converter.S_AXI <> S_AXI

  u_axi_trans_converter.read_reply_i.bits := fifo_i.bits
  u_axi_trans_converter.read_reply_i.valid := u_axi_trans_converter.read_request_o.valid && fifo_i.valid
  u_axi_trans_converter.read_request_o.ready := fifo_i.valid 
  fifo_i.ready := u_axi_trans_converter.read_reply_i.fire()

  fifo_o.bits := u_axi_trans_converter.write_request_o.bits.data
  fifo_o.valid := u_axi_trans_converter.write_request_o.valid
  u_axi_trans_converter.write_request_o.ready := fifo_o.ready

}
