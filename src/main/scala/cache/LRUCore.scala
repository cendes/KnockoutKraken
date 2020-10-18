package armflex.cache


import armflex.util._

import chisel3._
import chisel3.util._

/**
 *  the interface of LRU updating logic. Pure combinational logic.
 *  @param wayNumber how many ways this LRU could handle.
 */ 
abstract class LRUCore(wayNumber: Int) extends Module{
  def encodingWidth(): Int  // how many bits are needed to store the encoding bits.
  val wayWidth = log2Ceil(wayNumber)
  final val io = IO(new Bundle{
    // the request
    val index_i = Input(UInt(wayWidth.W))
    val index_vi = Input(Bool()) // a given index suggests that this is a hit and we'll update lru accordingly. 
    val vi = Input(Bool()) // if request is valid but index is not valid, we think this request as an allocation of the new place so we take lru_o as index.

    // lru out
    val lru_o = Output(UInt(wayWidth.W)) // lru_o should be only determined by encodings_i since it points to the current available place before the request is processed.

    // encoding in and out
    val encoding_i = Input(UInt(encodingWidth.W)) // the input encoding bits
    val encoding_o = Output(UInt(encodingWidth.W)) // the encoding bits that updated by the request. 
  })
}

/**
 *  Pseudo tree LRU updating logic. Implemented in recursive function instead of module.
 * 
 *  Tree LRU is appreciated when the associativity greater than 4, which can be normal in L1 TLB.
 */ 
class PseudoTreeLRUCore(wayNumber: Int) extends LRUCore(wayNumber){
  //assert(isPow2(wayNumber))
  override def encodingWidth(): Int = wayNumber - 1
  // lru_o, encoding_o
  def getLRU(start_index: Int, wayNumber: Int): UInt = {
    val start_bit = io.encoding_i(start_index)
    if(wayNumber == 2){
      return start_bit
    } else {
      val sub_encoding = Mux(start_bit, getLRU(start_index + wayNumber/2, wayNumber/2), getLRU(start_index + 1, wayNumber/2))
      return Cat(start_bit, sub_encoding)
    }
  }

  io.lru_o := getLRU(0, wayNumber)

  val update_term = Mux(io.index_vi, io.index_i, io.lru_o)
  val update_encoding = WireInit(io.encoding_i)
  def updateEncoding(start_index: Int, wayNumber: Int): Unit = {
    val wayWidth = log2Ceil(wayNumber)
    val start_bit = update_encoding(start_index)
    val judge_bit = update_term(wayWidth-1)
    if(wayNumber == 2){
      start_bit := Mux(start_bit === judge_bit, ~start_bit, start_bit)
    } else {
      when(judge_bit){
        updateEncoding(start_index + wayNumber / 2, wayNumber / 2)
      }.otherwise{
        updateEncoding(start_index + 1, wayNumber / 2)
      }
    }
  }

  io.encoding_o := Mux(io.vi, update_encoding, io.encoding_i)
}

/**
 *  Real LRU updating logic implemented by matrix. 
 */ 
class MatrixLRUCore(wayNumber: Int) extends LRUCore(wayNumber){
  override def encodingWidth(): Int = wayNumber * (wayNumber - 1)
  // 1. recover the matrix structure.
  val matrix = WireInit(VecInit(Seq.fill(wayNumber)(VecInit(Seq.fill(wayNumber)(false.B)))))
  var encoding_cnt = 0
  for(i <- 0 until wayNumber){
    for(j <- 0 until wayNumber){
      if(i == j){
        matrix(i)(j) := false.B
      } else {
        matrix(i)(j) := io.encoding_i(encoding_cnt)
        encoding_cnt += 1
      }
    }
  }

  // 2. update the bits accordingly.
  val update_term = Mux(io.index_vi, io.index_i, io.lru_o)
  val update_matrix = WireInit(VecInit(Seq.fill(wayNumber)(VecInit(Seq.fill(wayNumber)(false.B)))))
  for(i <- 0 until wayNumber){
    for(j <- 0 until wayNumber){
      if(i == j){
        update_matrix(i)(j) := false.B
      } else {
        when(i.U === update_term) {
          update_matrix(i)(j) := true.B
        }.elsewhen(j.U === update_term) {
          update_matrix(i)(j) := false.B
        }.otherwise{
          update_matrix(i)(j) := matrix(i)(j)
        }
      }
    }
  }
  
  // 3. output & flatten
  val all_zero = VecInit(matrix.map({x =>
    x.asUInt() === 0.U
  }))
  io.lru_o := PriorityEncoder(all_zero)

  val flattened_matrix = WireInit(VecInit(Seq.fill(encodingWidth())(false.B)))
  encoding_cnt = 0
  
  for(i <- 0 until wayNumber){
    for(j <- 0 until wayNumber){
      if(i != j){
        flattened_matrix(encoding_cnt) := update_matrix(i)(j)
        encoding_cnt += 1
      }
    }
  }

  io.encoding_o := Mux(io.vi, flattened_matrix.asUInt(), io.encoding_i)
}
