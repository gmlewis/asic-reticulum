package reticulum.crypto

import spinal.core._
import spinal.lib._

/**
 * SHA-256 State Bundle (a..h working variables, each 32 bits).
 */
case class Sha256State() extends Bundle {
  val a = UInt(32 bits)
  val b = UInt(32 bits)
  val c = UInt(32 bits)
  val d = UInt(32 bits)
  val e = UInt(32 bits)
  val f = UInt(32 bits)
  val g = UInt(32 bits)
  val h = UInt(32 bits)

  def toBits: Bits = Cat(a, b, c, d, e, f, g, h).asBits
  def toVec: Vec[UInt] = Vec(a, b, c, d, e, f, g, h)
}

/**
 * Bitwise transformation helpers for SHA-256 (FIPS 180-4).
 */
object Sha256Functions {
  def rotr(x: UInt, n: Int): UInt = (x |>> n) | (x |<< (32 - n))
  def sigma0(x: UInt): UInt = rotr(x, 2) ^ rotr(x, 13) ^ rotr(x, 22)
  def sigma1(x: UInt): UInt = rotr(x, 6) ^ rotr(x, 11) ^ rotr(x, 25)
  def ch(e: UInt, f: UInt, g: UInt): UInt = (e & f) ^ (~e & g)
  def maj(a: UInt, b: UInt, c: UInt): UInt = (a & b) ^ (a & c) ^ (b & c)
  def s0(x: UInt): UInt = rotr(x, 7) ^ rotr(x, 18) ^ (x |>> 3)
  def s1(x: UInt): UInt = rotr(x, 17) ^ rotr(x, 19) ^ (x |>> 10)
}

/**
 * Single-cycle SHA-256 compression round.
 *
 * Implements one step of the 64-round compression function:
 *   T1 = h + Sigma1(e) + Ch(e, f, g) + Kt + Wt
 *   T2 = Sigma0(a) + Maj(a, b, c)
 *   h' = g, g' = f, f' = e, e' = d + T1
 *   d' = c, c' = b, b' = a, a' = T1 + T2
 */
case class Sha256Round() extends Component {
  val io = new Bundle {
    val stateIn  = in(Sha256State())
    val w        = in UInt(32 bits)
    val k        = in UInt(32 bits)
    val stateOut = out(Sha256State())
  }

  import Sha256Functions._

  val t1 = io.stateIn.h + sigma1(io.stateIn.e) + ch(io.stateIn.e, io.stateIn.f, io.stateIn.g) + io.k + io.w
  val t2 = sigma0(io.stateIn.a) + maj(io.stateIn.a, io.stateIn.b, io.stateIn.c)

  io.stateOut.h := io.stateIn.g
  io.stateOut.g := io.stateIn.f
  io.stateOut.f := io.stateIn.e
  io.stateOut.e := io.stateIn.d + t1
  io.stateOut.d := io.stateIn.c
  io.stateOut.c := io.stateIn.b
  io.stateOut.b := io.stateIn.a
  io.stateOut.a := t1 + t2
}

/**
 * Generates synthesis-ready Verilog for the Sha256Round module.
 * Run with: sbt "runMain reticulum.crypto.Sha256RoundVerilog"
 */
object Sha256RoundVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  config.generateVerilog(Sha256Round()).printPruned()
  println("Successfully generated Verilog in hw/gen/Sha256Round.v")
}
