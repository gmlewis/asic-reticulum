package reticulum.fpga

import reticulum.bus.QspiTop
import spinal.core._
import spinal.lib._

/**
 * Top-level FPGA Hardware Accelerator module for Reticulum.
 *
 * Configured for FPGA development platforms (e.g. Sipeed Tang Primer 25K,
 * QMTECH AMD Artix-7, Lattice ECP5):
 *  - 4 parallel TokenEngine instances (N = 4) for high-throughput packet fanouts.
 *  - Full X25519 Montgomery ladder field arithmetic unit.
 *  - Autonomous IFAC Hashcash Stamp grinder.
 *  - 7-pin QSPI host interface directly wireable to ESP32-C5.
 *  - Hardware diagnostic outputs: heartbeat blinker, busy status, and IRQ indicator LEDs.
 */
case class FpgaTop(numEngines: Int = 4, roundsPerStage: Int = 1) extends Component {
  val io = new Bundle {
    // 7-Pin Host Interface (connected to ESP32-C5 via PMOD/headers)
    val qspi_sclk     = in Bool()
    val qspi_cs_n     = in Bool()
    val qspi_data_in  = in Bits(4 bits)
    val qspi_data_out = out Bits(4 bits)
    val qspi_data_oe  = out Bool()
    val qspi_irq_n    = out Bool()

    // Diagnostic LEDs
    val led_heartbeat = out Bool()
    val led_busy      = out Bool()
    val led_irq       = out Bool()
  }

  val qspi = QspiTop(roundsPerStage = roundsPerStage, numEngines = numEngines)

  qspi.io.sclk        := io.qspi_sclk
  qspi.io.cs_n        := io.qspi_cs_n
  qspi.io.data_in     := io.qspi_data_in
  io.qspi_data_out    := qspi.io.data_out
  io.qspi_data_oe     := qspi.io.data_oe
  io.qspi_irq_n       := qspi.io.irq_n

  // Heartbeat counter (~1.5 Hz blink at 50 MHz)
  val heartbeatCounter = Reg(UInt(25 bits)) init(0)
  heartbeatCounter := heartbeatCounter + 1
  io.led_heartbeat := heartbeatCounter.msb

  // Status LEDs
  io.led_busy := !qspi.io.irq_n || qspi.io.busy
  io.led_irq  := !qspi.io.irq_n
}

/**
 * Verilog generator companion object for FpgaTop.
 * Run with: sbt "runMain reticulum.fpga.FpgaTopVerilog"
 */
object FpgaTopVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )
  config.generateVerilog(FpgaTop(numEngines = 4, roundsPerStage = 1)).printPruned()
  println("Successfully generated FPGA Verilog in hw/gen/FpgaTop.v")
}
