package reticulum.bus

import reticulum.crypto.Stamper
import spinal.core._
import spinal.lib._

/**
 * Top-level 7-pin physical interface for the Reticulum ASIC.
 *
 *  - sclk: Serial clock from host (Mode 0)
 *  - cs_n: Active-low chip select
 *  - data_in: 4-bit data input bus (IO0..IO3)
 *  - data_out: 4-bit data output bus (IO0..IO3)
 *  - data_oe: Active-high output enable (for bidirectional GPIO pads)
 *  - irq_n: Dedicated active-low interrupt line to host
 */
case class QspiTopIo() extends Bundle {
  val sclk     = in Bool()
  val cs_n     = in Bool()
  val data_in  = in Bits(4 bits)
  val data_out = out Bits(4 bits)
  val data_oe  = out Bool()
  val irq_n    = out Bool()
}

/**
 * Top-level ASIC Interconnect & Cryptographic Offload Engine.
 *
 * Integrates:
 *  1. 4-bit Quad-SPI Slave transceiver (QspiSlave).
 *  2. Command Decoder FSM (QspiCommandDecoder).
 *  3. Autonomous IFAC Hashcash Stamp Grinder (Stamper).
 *
 * Provides a 7-pin physical interface to host microcontrollers (e.g. ESP32-C5 GDMA):
 *  - High-speed 4-bit streaming at 20-40 MB/s wire speed.
 *  - Zero host polling via dedicated active-low hardware interrupt (irq_n).
 */
case class QspiTop(roundsPerStage: Int = 1) extends Component {
  val io = QspiTopIo()

  val slave   = QspiSlave()
  val decoder = QspiCommandDecoder()
  val stamper = Stamper(roundsPerStage = roundsPerStage)

  // -------------------------------------------------------------------------
  // Physical Pad Connections
  // -------------------------------------------------------------------------
  slave.io.sclk    := io.sclk
  slave.io.cs_n    := io.cs_n
  slave.io.data_in := io.data_in
  io.data_out      := slave.io.data_out
  io.data_oe       := slave.io.data_oe

  decoder.io.cs_n := io.cs_n

  // Dedicated active-low interrupt to host
  io.irq_n := !stamper.io.irq

  // -------------------------------------------------------------------------
  // QSPI Slave <-> Command Decoder Interconnect
  // -------------------------------------------------------------------------
  decoder.io.rx << slave.io.rx
  slave.io.tx   << decoder.io.tx
  slave.io.txEnable := decoder.io.txEnable

  // -------------------------------------------------------------------------
  // Command Decoder <-> Stamper Interconnect
  // -------------------------------------------------------------------------
  stamper.io.start           := decoder.io.stampStart
  stamper.io.abort           := decoder.io.stampAbort
  stamper.io.irqClear        := decoder.io.stampIrqClear
  stamper.io.targetCost      := decoder.io.stampTargetCost
  stamper.io.midstate        := decoder.io.stampMidstate
  stamper.io.baseCandidate   := decoder.io.stampBaseCandidate
  stamper.io.totalLengthBits := decoder.io.stampTotalLengthBits
  stamper.io.startNonce      := decoder.io.stampStartNonce
  stamper.io.maxRounds       := decoder.io.stampMaxRounds

  decoder.io.stampBusy             := stamper.io.busy
  decoder.io.stampDone             := stamper.io.done
  decoder.io.stampMeetsTarget      := stamper.io.meetsTarget
  decoder.io.stampIrq              := stamper.io.irq
  decoder.io.stampWinningCandidate := stamper.io.winningCandidate
  decoder.io.stampWinningDigest    := stamper.io.winningDigest
  decoder.io.stampWinningZeros     := stamper.io.winningZeros
  decoder.io.stampWinningNonce     := stamper.io.winningNonce
  decoder.io.stampRoundsEvaluated  := stamper.io.roundsEvaluated
}

/**
 * Generates synthesis-ready Verilog for the top-level QspiTop ASIC module.
 * Run with: sbt "runMain reticulum.bus.QspiTopVerilog"
 */
object QspiTopVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  config.generateVerilog(QspiTop()).printPruned()
  println("Successfully generated Verilog in hw/gen/QspiTop.v")
}
