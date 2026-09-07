package reticulum.crypto

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/**
 * Iterative 10-round AES-128 core (FIPS 197).
 *
 * Supports both Encryption and Decryption (Standard Inverse Cipher).
 * Execution latency:
 *  - Key expansion: 10 clock cycles
 *  - Block encrypt: 10 clock cycles
 *  - Block decrypt: 10 clock cycles
 *
 * Throughput: ~80 MB/s at 50 MHz.
 */
case class AesCore() extends Component {
  val io = new Bundle {
    val key      = in Bits(128 bits)
    val blockIn  = in Bits(128 bits)
    val enc      = in Bool() // True = encrypt, False = decrypt
    val loadKey  = in Bool() // Pulse to start key expansion
    val start    = in Bool() // Pulse to start block encrypt/decrypt
    val blockOut = out Bits(128 bits)
    val busy     = out Bool()
    val done     = out Bool()
    val keyReady = out Bool()
  }

  // S-Box and InvS-Box lookup tables
  val sboxLut    = Vec(AesConstants.SBOX.map(v => B(v, 8 bits)))
  val invSboxLut = Vec(AesConstants.INVSBOX.map(v => B(v, 8 bits)))
  val rconLut    = Vec(AesConstants.RCON.map(v => B(v, 8 bits)))

  def sbox(b: Bits): Bits    = sboxLut(b.asUInt)
  def invSbox(b: Bits): Bits = invSboxLut(b.asUInt)

  // Galois Field GF(2^8) arithmetic
  def xtime(b: Bits): Bits = {
    val shifted = (b(6 downto 0) ## False)
    val msb = b(7)
    shifted ^ (Mux(msb, B(0x1b, 8 bits), B(0x00, 8 bits)))
  }
  def mul2(b: Bits): Bits  = xtime(b)
  def mul3(b: Bits): Bits  = xtime(b) ^ b
  def mul4(b: Bits): Bits  = xtime(mul2(b))
  def mul8(b: Bits): Bits  = xtime(mul4(b))
  def mul9(b: Bits): Bits  = mul8(b) ^ b
  def mul11(b: Bits): Bits = mul8(b) ^ mul2(b) ^ b
  def mul13(b: Bits): Bits = mul8(b) ^ mul4(b) ^ b
  def mul14(b: Bits): Bits = mul8(b) ^ mul4(b) ^ mul2(b)

  def mixCol(c0: Bits, c1: Bits, c2: Bits, c3: Bits): (Bits, Bits, Bits, Bits) = {
    val d0 = mul2(c0) ^ mul3(c1) ^ c2 ^ c3
    val d1 = c0 ^ mul2(c1) ^ mul3(c2) ^ c3
    val d2 = c0 ^ c1 ^ mul2(c2) ^ mul3(c3)
    val d3 = mul3(c0) ^ c1 ^ c2 ^ mul2(c3)
    (d0, d1, d2, d3)
  }

  def invMixCol(c0: Bits, c1: Bits, c2: Bits, c3: Bits): (Bits, Bits, Bits, Bits) = {
    val d0 = mul14(c0) ^ mul11(c1) ^ mul13(c2) ^ mul9(c3)
    val d1 = mul9(c0) ^ mul14(c1) ^ mul11(c2) ^ mul13(c3)
    val d2 = mul13(c0) ^ mul9(c1) ^ mul14(c2) ^ mul11(c3)
    val d3 = mul11(c0) ^ mul13(c1) ^ mul9(c2) ^ mul14(c3)
    (d0, d1, d2, d3)
  }

  def unpackBytes(block: Bits): Vec[Bits] = {
    val v = Vec(Bits(8 bits), 16)
    for (i <- 0 until 16) {
      v(i) := block(127 - i * 8 downto 120 - i * 8)
    }
    v
  }

  def packBytes(v: Vec[Bits]): Bits = {
    val res = Bits(128 bits)
    for (i <- 0 until 16) {
      res(127 - i * 8 downto 120 - i * 8) := v(i)
    }
    res
  }

  // Key storage: 11 round keys of 128 bits each
  val roundKeys    = Vec(Reg(Bits(128 bits)) init(0), 11)
  val keyValid     = RegInit(False)
  val keyExpandCnt = Reg(UInt(4 bits)) init(0)

  // Block state register: 16 bytes
  val blockState  = Vec(Reg(Bits(8 bits)) init(0), 16)
  val roundCnt    = Reg(UInt(4 bits)) init(0)
  val blockOutReg = Reg(Bits(128 bits)) init(0)
  val doneReg     = RegInit(False)
  val encModeReg  = RegInit(True)

  io.blockOut := blockOutReg
  io.done     := doneReg
  io.keyReady := keyValid

  // Combinational encryption round logic from blockState
  val encSubBytes = Vec(Bits(8 bits), 16)
  for (i <- 0 until 16) {
    encSubBytes(i) := sbox(blockState(i))
  }

  val encShiftRows = Vec(Bits(8 bits), 16)
  encShiftRows(0)  := encSubBytes(0)
  encShiftRows(1)  := encSubBytes(5)
  encShiftRows(2)  := encSubBytes(10)
  encShiftRows(3)  := encSubBytes(15)
  encShiftRows(4)  := encSubBytes(4)
  encShiftRows(5)  := encSubBytes(9)
  encShiftRows(6)  := encSubBytes(14)
  encShiftRows(7)  := encSubBytes(3)
  encShiftRows(8)  := encSubBytes(8)
  encShiftRows(9)  := encSubBytes(13)
  encShiftRows(10) := encSubBytes(2)
  encShiftRows(11) := encSubBytes(7)
  encShiftRows(12) := encSubBytes(12)
  encShiftRows(13) := encSubBytes(1)
  encShiftRows(14) := encSubBytes(6)
  encShiftRows(15) := encSubBytes(11)

  val encMixCols = Vec(Bits(8 bits), 16)
  for (c <- 0 until 4) {
    val (d0, d1, d2, d3) = mixCol(
      encShiftRows(4 * c),
      encShiftRows(4 * c + 1),
      encShiftRows(4 * c + 2),
      encShiftRows(4 * c + 3)
    )
    encMixCols(4 * c)     := d0
    encMixCols(4 * c + 1) := d1
    encMixCols(4 * c + 2) := d2
    encMixCols(4 * c + 3) := d3
  }

  val curEncRoundKey = unpackBytes(roundKeys(roundCnt))
  val encNextState = Vec(Bits(8 bits), 16)
  val encPreKey = Mux(roundCnt === 10, encShiftRows, encMixCols)
  for (i <- 0 until 16) {
    encNextState(i) := encPreKey(i) ^ curEncRoundKey(i)
  }

  // Combinational decryption round logic from blockState
  val decInvShiftRows = Vec(Bits(8 bits), 16)
  decInvShiftRows(0)  := blockState(0)
  decInvShiftRows(1)  := blockState(13)
  decInvShiftRows(2)  := blockState(10)
  decInvShiftRows(3)  := blockState(7)
  decInvShiftRows(4)  := blockState(4)
  decInvShiftRows(5)  := blockState(1)
  decInvShiftRows(6)  := blockState(14)
  decInvShiftRows(7)  := blockState(11)
  decInvShiftRows(8)  := blockState(8)
  decInvShiftRows(9)  := blockState(5)
  decInvShiftRows(10) := blockState(2)
  decInvShiftRows(11) := blockState(15)
  decInvShiftRows(12) := blockState(12)
  decInvShiftRows(13) := blockState(9)
  decInvShiftRows(14) := blockState(6)
  decInvShiftRows(15) := blockState(3)

  val decInvSubBytes = Vec(Bits(8 bits), 16)
  for (i <- 0 until 16) {
    decInvSubBytes(i) := invSbox(decInvShiftRows(i))
  }

  val curDecRoundKey = unpackBytes(roundKeys(roundCnt))
  val decAddRoundKey = Vec(Bits(8 bits), 16)
  for (i <- 0 until 16) {
    decAddRoundKey(i) := decInvSubBytes(i) ^ curDecRoundKey(i)
  }

  val decInvMixCols = Vec(Bits(8 bits), 16)
  for (c <- 0 until 4) {
    val (d0, d1, d2, d3) = invMixCol(
      decAddRoundKey(4 * c),
      decAddRoundKey(4 * c + 1),
      decAddRoundKey(4 * c + 2),
      decAddRoundKey(4 * c + 3)
    )
    decInvMixCols(4 * c)     := d0
    decInvMixCols(4 * c + 1) := d1
    decInvMixCols(4 * c + 2) := d2
    decInvMixCols(4 * c + 3) := d3
  }

  val decNextState = Mux(roundCnt === 0, decAddRoundKey, decInvMixCols)

  // Single-cycle Key Expansion step
  val prevKey = roundKeys(keyExpandCnt - 1)
  val w0 = prevKey(127 downto 96)
  val w1 = prevKey(95 downto 64)
  val w2 = prevKey(63 downto 32)
  val w3 = prevKey(31 downto 0)

  val rotWord = w3(23 downto 16) ## w3(15 downto 8) ## w3(7 downto 0) ## w3(31 downto 24)
  val sub0 = sbox(rotWord(31 downto 24))
  val sub1 = sbox(rotWord(23 downto 16))
  val sub2 = sbox(rotWord(15 downto 8))
  val sub3 = sbox(rotWord(7 downto 0))
  val rconByte = rconLut(keyExpandCnt)
  val tempWord = (sub0 ^ rconByte) ## sub1 ## sub2 ## sub3

  val nw0 = w0 ^ tempWord
  val nw1 = w1 ^ nw0
  val nw2 = w2 ^ nw1
  val nw3 = w3 ^ nw2
  val nextExpandedKey = nw0 ## nw1 ## nw2 ## nw3

  // FSM controller
  val fsm = new StateMachine {
    val sIdle: State      = new State with EntryPoint
    val sKeyExpand: State = new State
    val sEncRound: State  = new State
    val sDecRound: State  = new State

    io.busy := !isActive(sIdle)

    sIdle.whenIsActive {
      doneReg := False
      when(io.loadKey) {
        roundKeys(0) := io.key
        keyExpandCnt := 1
        keyValid     := False
        goto(sKeyExpand)
      } elsewhen(io.start) {
        encModeReg := io.enc
        when(io.enc) {
          val initBytes = unpackBytes(io.blockIn ^ roundKeys(0))
          for (i <- 0 until 16) {
            blockState(i) := initBytes(i)
          }
          roundCnt := 1
          goto(sEncRound)
        } otherwise {
          val initBytes = unpackBytes(io.blockIn ^ roundKeys(10))
          for (i <- 0 until 16) {
            blockState(i) := initBytes(i)
          }
          roundCnt := 9
          goto(sDecRound)
        }
      }
    }

    sKeyExpand.whenIsActive {
      roundKeys(keyExpandCnt) := nextExpandedKey
      when(keyExpandCnt === 10) {
        keyValid := True
        goto(sIdle)
      } otherwise {
        keyExpandCnt := keyExpandCnt + 1
      }
    }

    sEncRound.whenIsActive {
      when(roundCnt === 10) {
        blockOutReg := packBytes(encNextState)
        doneReg     := True
        goto(sIdle)
      } otherwise {
        for (i <- 0 until 16) {
          blockState(i) := encNextState(i)
        }
        roundCnt := roundCnt + 1
      }
    }

    sDecRound.whenIsActive {
      when(roundCnt === 0) {
        blockOutReg := packBytes(decNextState)
        doneReg     := True
        goto(sIdle)
      } otherwise {
        for (i <- 0 until 16) {
          blockState(i) := decNextState(i)
        }
        roundCnt := roundCnt - 1
      }
    }
  }
}

/**
 * Companion object for generating Verilog.
 */
object AesCoreVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )
  config.generateVerilog(AesCore()).printPruned()
  println("Successfully generated Verilog in hw/gen/AesCore.v")
}
