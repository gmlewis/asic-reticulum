package reticulum.crypto

import spinal.core._
import spinal.lib._

/**
 * Prime field GF(2^255 - 19) arithmetic primitives for Curve25519 / X25519 / Ed25519.
 *
 * Implements constant-time field operations without division using the pseudo-Mersenne
 * property 2^255 = 19 (mod 2^255 - 19).
 */
object Field25519 {
  val Prime: BigInt = (BigInt(1) << 255) - 19
  val A24: BigInt   = 121665 // (486662 - 2) / 4 for Montgomery curve v^2 = u^3 + 486662*u^2 + u

  /**
   * Modular addition: (a + b) mod (2^255 - 19).
   * Inputs a, b are expected to be in [0, 2^255 - 20].
   */
  def add(a: UInt, b: UInt): UInt = {
    val sumRaw    = a + b // 256 bits
    val sumPlus19 = (sumRaw.resize(257) + 19).resize(257)
    val result    = UInt(256 bits)
    when(sumPlus19 >= (BigInt(1) << 255)) {
      result := sumPlus19(254 downto 0).resize(256)
    } otherwise {
      result := sumRaw
    }
    result
  }

  /**
   * Modular subtraction: (a - b) mod (2^255 - 19).
   * Inputs a, b are expected to be in [0, 2^255 - 20].
   */
  def sub(a: UInt, b: UInt): UInt = {
    val diff = UInt(256 bits)
    when(a >= b) {
      diff := a - b
    } otherwise {
      // a - b + (2^255 - 19)
      diff := (a.resize(257) + (BigInt(1) << 255) - 19 - b.resize(257)).resize(256)
    }
    diff
  }

  /**
   * 512-bit modular reduction modulo 2^255 - 19.
   *
   * Given P = P_hi * 2^255 + P_lo:
   *   P = P_lo + 19 * P_hi (mod 2^255 - 19)
   *
   * Recursively folds high bits with 19x scaling until fully reduced.
   */
  def reduce512(prod: UInt): UInt = {
    val pLo = prod(254 downto 0)
    val pHi = prod(511 downto 255) // up to 257 bits

    // M = 19 * pHi = (pHi << 4) + (pHi << 1) + pHi
    val m = ((pHi.resize(262) << 4) + (pHi.resize(262) << 1) + pHi.resize(262)).resize(262)

    val mLo    = m(254 downto 0)
    val mHi    = m(261 downto 255) // at most 7 bits (<= 127)
    val mHi19  = (mHi.resize(12) * 19).resize(12)

    // S1 = pLo + mLo + mHi19
    val s1     = (pLo.resize(258) + mLo.resize(258) + mHi19.resize(258)).resize(258)
    val s1Lo   = s1(254 downto 0)
    val s1Hi   = s1(257 downto 255) // at most 3 bits (<= 7)
    val s1Hi19 = (s1Hi.resize(8) * 19).resize(8)

    // S2 = s1Lo + s1Hi19
    val s2       = (s1Lo.resize(257) + s1Hi19.resize(257)).resize(257)
    val s2Plus19 = (s2 + 19).resize(257)

    val result = UInt(256 bits)
    when(s2Plus19 >= (BigInt(1) << 255)) {
      result := s2Plus19(254 downto 0).resize(256)
    } otherwise {
      result := s2(254 downto 0).resize(256)
    }
    result
  }

  /**
   * Combinational modular multiplication: (a * b) mod (2^255 - 19).
   */
  def mul(a: UInt, b: UInt): UInt = {
    val prod = (a.resize(256) * b.resize(256)).resize(512)
    reduce512(prod)
  }

  /**
   * Combinational modular squaring: (a^2) mod (2^255 - 19).
   */
  def sqr(a: UInt): UInt = mul(a, a)

  /**
   * Modular scaling by curve constant a24 = 121665.
   */
  def mulA24(a: UInt): UInt = {
    val prod = (a.resize(256) * U(A24, 256 bits)).resize(512)
    reduce512(prod)
  }
}

/**
 * Pipelined/registered Field Multiplier Component for GF(2^255 - 19).
 *
 * Latency: 1 cycle from start to valid/result.
 */
case class FieldMultiplier() extends Component {
  val io = new Bundle {
    val start  = in Bool()
    val a      = in UInt(256 bits)
    val b      = in UInt(256 bits)
    val valid  = out Bool()
    val result = out UInt(256 bits)
  }

  val regValid  = RegInit(False)
  val regResult = Reg(UInt(256 bits)) init (0)

  when(io.start) {
    val prod = (io.a.resize(256) * io.b.resize(256)).resize(512)
    regResult := Field25519.reduce512(prod)
    regValid  := True
  } otherwise {
    regValid  := False
  }

  io.valid  := regValid
  io.result := regResult
}

/**
 * Generates synthesis-ready Verilog for FieldMultiplier.
 * Run with: sbt "runMain reticulum.crypto.FieldMultiplierVerilog"
 */
object FieldMultiplierVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )
  config.generateVerilog(FieldMultiplier())
}
