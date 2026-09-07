/*
 * Copyright 2026 Glenn Lewis. All rights reserved.
 * Use of this source code is governed by the BSD-style
 * license that can be found in the LICENSE file.
 */

package reticulum.tt

import reticulum.bus.QspiTop
import spinal.core._
import spinal.lib._

/**
 * Top-level ASIC wrapper conforming to the Tiny Tapeout hardware interface specification.
 *
 * Tiny Tapeout Standard Interface:
 *  - ui_in[7:0]   : Dedicated input pins
 *  - uo_out[7:0]  : Dedicated output pins
 *  - uio_in[7:0]  : Bidirectional input path
 *  - uio_out[7:0] : Bidirectional output path
 *  - uio_oe[7:0]  : Bidirectional output enable (1 = output, 0 = input/high-Z)
 *  - ena          : Design enable (active high from multiplexer)
 *  - clk          : System clock (typically 20-50 MHz)
 *  - rst_n        : System reset (active low)
 *
 * Pin Allocations:
 *  - ui_in[0]     : QSPI SCLK (Mode 0)
 *  - ui_in[1]     : QSPI CS# (Active-low chip select)
 *  - ui_in[7:2]   : Reserved inputs (tied off internally)
 *  - uio[3:0]     : Bidirectional 4-bit Quad-SPI data bus (IO0..IO3)
 *  - uio[7:4]     : Reserved bidirectional pins (configured as high-Z inputs)
 *  - uo_out[0]    : QSPI IRQ# (Active-low asynchronous completion interrupt)
 *  - uo_out[1]    : Busy indicator (Active-high while any crypto core is calculating)
 *  - uo_out[2]    : Diagnostic heartbeat (~1.5 Hz blinker at 50 MHz)
 *  - uo_out[7:3]  : Reserved status outputs (driven to 0)
 */
case class tt_um_gmlewis_reticulum() extends Component {
  val io = new Bundle {
    val ui_in   = in Bits(8 bits)
    val uo_out  = out Bits(8 bits)
    val uio_in  = in Bits(8 bits)
    val uio_out = out Bits(8 bits)
    val uio_oe  = out Bits(8 bits)
    val ena     = in Bool()
  }
  noIoPrefix()

  // Rename clock domain signals to conform to Tiny Tapeout naming conventions
  ClockDomain.current.clock.setName("clk")
  ClockDomain.current.reset.setName("rst_n")

  // Instantiates single-engine TokenEngine and 1-round SHA256 pipe for Tiny Tapeout silicon budget
  val qspi = QspiTop(roundsPerStage = 1, numEngines = 1)

  // Dedicated inputs
  qspi.io.sclk := io.ui_in(0)
  qspi.io.cs_n := io.ui_in(1)

  // Bidirectional 4-bit Quad-SPI Data Bus on uio[3:0]
  qspi.io.data_in := io.uio_in(3 downto 0)
  io.uio_out      := B"4'b0000" ## qspi.io.data_out
  io.uio_oe       := B"4'b0000" ## B(4 bits, default -> qspi.io.data_oe)

  // Diagnostic heartbeat generator (~1.5 Hz blink at 50 MHz)
  val heartbeatCounter = Reg(UInt(25 bits)) init(0)
  heartbeatCounter := heartbeatCounter + 1
  val heartbeat = heartbeatCounter.msb

  // Dedicated outputs on uo_out
  io.uo_out(0) := qspi.io.irq_n
  io.uo_out(1) := qspi.io.busy
  io.uo_out(2) := heartbeat
  io.uo_out(7 downto 3) := 0
}

/**
 * Verilog generator companion object for Tiny Tapeout submission.
 * Emits hw/gen/tt_um_gmlewis_reticulum.v
 *
 * Run with: sbt "runMain reticulum.tt.TinyTapeoutVerilog"
 */
object TinyTapeoutVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = ASYNC,
      resetActiveLevel = LOW
    )
  )

  config.generateVerilog(tt_um_gmlewis_reticulum()).printPruned()
  println("Successfully generated Tiny Tapeout Verilog in hw/gen/tt_um_gmlewis_reticulum.v")

  // Ensure src/ directory exists and synchronize Verilog for standard Tiny Tapeout flow
  val srcDir = java.nio.file.Paths.get("src")
  if (!java.nio.file.Files.exists(srcDir)) {
    java.nio.file.Files.createDirectories(srcDir)
  }
  java.nio.file.Files.copy(
    java.nio.file.Paths.get("hw/gen/tt_um_gmlewis_reticulum.v"),
    java.nio.file.Paths.get("src/tt_um_gmlewis_reticulum.v"),
    java.nio.file.StandardCopyOption.REPLACE_EXISTING
  )
  println("Successfully synced Verilog to src/tt_um_gmlewis_reticulum.v")
}
