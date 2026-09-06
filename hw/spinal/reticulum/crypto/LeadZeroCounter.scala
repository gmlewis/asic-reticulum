package reticulum.crypto

import spinal.core._
import spinal.lib._

/**
 * LeadZeroCounter (LZC) / Difficulty Comparator for Reticulum IFAC Hashcash Stamps.
 *
 * In Reticulum LXMF, proof-of-work stamps (hashcash) require finding a nonce such that
 * the SHA-256 hash of the workblock has at least `target` leading zero bits.
 *
 * This hardware component evaluates a 256-bit candidate hash in a single cycle:
 *   - Calculates the exact count of leading zero bits from MSB to LSB.
 *   - Compares the count against the target difficulty threshold.
 *
 * @param width Bit-width of the input hash (default: 256 bits).
 */
case class LeadZeroCounter(width: Int = 256) extends Component {
  val io = new Bundle {
    val hash         = in Bits(width bits)
    val target       = in UInt(log2Up(width + 1) bits)
    val leadingZeros = out UInt(log2Up(width + 1) bits)
    val meetsTarget  = out Bool()
  }

  // Count consecutive zero bits starting from the Most Significant Bit (MSB).
  io.leadingZeros := CountLeadingZeroes(io.hash)

  // Assert meetsTarget if the candidate hash difficulty meets or exceeds target.
  io.meetsTarget := io.leadingZeros >= io.target
}

/**
 * Generates synthesis-ready Verilog for the LeadZeroCounter module.
 * Run with: sbt "runMain reticulum.crypto.LeadZeroCounterVerilog"
 */
object LeadZeroCounterVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  config.generateVerilog(LeadZeroCounter(256)).printPruned()
  println("Successfully generated Verilog in hw/gen/LeadZeroCounter.v")
}
