// See LICENSE for license details.

package armflex.demander.mini

import chisel3.Module
// import freechips.rocketchip.config.{Parameters, Config}
// import junctions._

// class MiniConfig extends Config((site, here, up) => {
//     // Core
//     case XLEN => 32
//     case Trace => true
//     case BuildALU    => (p: Parameters) => Module(new ALUArea()(p))
//     case BuildImmGen => (p: Parameters) => Module(new ImmGenWire()(p))
//     case BuildBrCond => (p: Parameters) => Module(new BrCondArea()(p))
//     // Cache
//     case NWays => 1 // TODO: set-associative
//     case NSets => 256 
//     case CacheBlockBytes => 4 * (here(XLEN) >> 3) // 4 x 32 bits = 16B
//     // NastiIO
//     case NastiKey => new NastiParameters(
//       idBits   = 5,
//       dataBits = 64,
//       addrBits = here(XLEN))
//   }
// )

class MiniConfig(val initialPC: Int = 0x0) {
  val XLEN = 32

  val BuildALU = () => Module(new ALUArea()(this))
  val BuildImmGen = () => Module(new ImmGenWire()(this))
  val BuildBrCond = () => Module(new BrCondArea()(this))

  val Trace: Boolean = true
}