/*
 * Copyright 2026 Glenn Lewis. All rights reserved.
 * Use of this source code is governed by the BSD-style
 * license that can be found in the LICENSE file.
 */

package reticulum.tt

import org.scalatest.funsuite.AnyFunSuite
import reticulum.bus.QspiOpcode
import spinal.core._
import spinal.core.sim._

class TinyTapeoutTopTest extends AnyFunSuite {

  def hexToBytes(hex: String): Seq[Int] = {
    hex.grouped(2).map(Integer.parseInt(_, 16)).toSeq
  }

  def bytesToHex(bytes: Seq[Int]): String = {
    bytes.map(b => f"$b%02x").mkString
  }

  /**
   * Helper: Simulates host writing a byte over Tiny Tapeout QSPI interface.
   * Mode 0: sclk starts low, data sampled on rising edge.
   */
  def ttQspiWriteByte(dut: tt_um_gmlewis_reticulum, byteVal: Int): Unit = {
    val highNibble = (byteVal >> 4) & 0x0F
    val lowNibble  = byteVal & 0x0F

    // Cycle 1: High nibble on uio_in[3:0]
    dut.io.uio_in #= highNibble
    dut.clockDomain.waitSampling(2)
    dut.io.ui_in #= (dut.io.ui_in.toInt | 0x01) // sclk = 1 (ui_in[0])
    dut.clockDomain.waitSampling(2)
    dut.io.ui_in #= (dut.io.ui_in.toInt & ~0x01) // sclk = 0
    dut.clockDomain.waitSampling(2)

    // Cycle 2: Low nibble on uio_in[3:0]
    dut.io.uio_in #= lowNibble
    dut.clockDomain.waitSampling(2)
    dut.io.ui_in #= (dut.io.ui_in.toInt | 0x01) // sclk = 1
    dut.clockDomain.waitSampling(2)
    dut.io.ui_in #= (dut.io.ui_in.toInt & ~0x01) // sclk = 0
    dut.clockDomain.waitSampling(2)
  }

  /**
   * Helper: Simulates host reading a byte over Tiny Tapeout QSPI interface.
   */
  def ttQspiReadByte(dut: tt_um_gmlewis_reticulum): Int = {
    // Cycle 1: High nibble on uio_out[3:0]
    dut.clockDomain.waitSampling(4)
    dut.io.ui_in #= (dut.io.ui_in.toInt | 0x01) // sclk = 1
    dut.clockDomain.waitSampling(2)
    val highNibble = dut.io.uio_out.toInt & 0x0F
    dut.clockDomain.waitSampling(2)
    dut.io.ui_in #= (dut.io.ui_in.toInt & ~0x01) // sclk = 0

    // Cycle 2: Low nibble on uio_out[3:0]
    dut.clockDomain.waitSampling(4)
    dut.io.ui_in #= (dut.io.ui_in.toInt | 0x01) // sclk = 1
    dut.clockDomain.waitSampling(2)
    val lowNibble = dut.io.uio_out.toInt & 0x0F
    dut.clockDomain.waitSampling(2)
    dut.io.ui_in #= (dut.io.ui_in.toInt & ~0x01) // sclk = 0

    (highNibble << 4) | lowNibble
  }

  def ttQspiSendCommand(dut: tt_um_gmlewis_reticulum, opcode: Int, payload: Seq[Int] = Seq()): Unit = {
    val lenMsb = (payload.length >> 8) & 0xFF
    val lenLsb = payload.length & 0xFF

    // Assert CS# low (ui_in[1] = 0)
    dut.io.ui_in #= (dut.io.ui_in.toInt & ~0x02)
    dut.clockDomain.waitSampling(4)

    ttQspiWriteByte(dut, opcode)
    ttQspiWriteByte(dut, lenMsb)
    ttQspiWriteByte(dut, lenLsb)
    for (b <- payload) {
      ttQspiWriteByte(dut, b)
    }

    dut.clockDomain.waitSampling(4)
    // Deassert CS# high (ui_in[1] = 1)
    dut.io.ui_in #= (dut.io.ui_in.toInt | 0x02)
    dut.clockDomain.waitSampling(5)
  }

  def initDut(dut: tt_um_gmlewis_reticulum): Unit = {
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.ena    #= true
    dut.io.ui_in  #= 0x02 // sclk=0, cs_n=1
    dut.io.uio_in #= 0x00
    dut.clockDomain.waitSampling(10)
  }

  val simConfig = SimConfig.withConfig(
    SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge        = RISING,
        resetKind        = ASYNC,
        resetActiveLevel = LOW
      )
    )
  )

  test("TinyTapeoutTop: Pin Mapping, Reset, and Tri-State Bus Turnaround") {
    simConfig.compile(tt_um_gmlewis_reticulum()).doSim { dut =>
      initDut(dut)

      // Initial state: CS is high, IRQ# is high, busy is low, uio_oe is 0 (input mode)
      assert((dut.io.uo_out.toInt & 0x01) == 1, "uo_out[0] (irq_n) should be high initially")
      assert((dut.io.uo_out.toInt & 0x02) == 0, "uo_out[1] (busy) should be low initially")
      assert((dut.io.uio_oe.toInt & 0x0F) == 0, "uio_oe[3:0] must be 0 (inputs) while idle")

      // Query status via OP_STATUS
      dut.io.ui_in #= (dut.io.ui_in.toInt & ~0x02) // CS# = 0
      dut.clockDomain.waitSampling(4)

      ttQspiWriteByte(dut, QspiOpcode.OP_STATUS)
      ttQspiWriteByte(dut, 0x00)
      ttQspiWriteByte(dut, 0x00)

      // Wait for pipeline turnaround to TX state
      dut.clockDomain.waitSampling(8)

      // ASIC now drives uio_out; uio_oe[3:0] must be 0x0F
      assert((dut.io.uio_oe.toInt & 0x0F) == 0x0F, "uio_oe[3:0] must assert during readout")

      val st0 = ttQspiReadByte(dut)
      val st1 = ttQspiReadByte(dut)
      val st2 = ttQspiReadByte(dut)
      val st3 = ttQspiReadByte(dut)

      assert(st1 == 0x10, f"Expected arch version 0x10 in status byte 1, got 0x$st1%02x")

      // Deassert CS#
      dut.io.ui_in #= (dut.io.ui_in.toInt | 0x02)
      dut.clockDomain.waitSampling(5)

      // uio_oe immediately releases to 0
      assert((dut.io.uio_oe.toInt & 0x0F) == 0, "uio_oe must release immediately on CS# deassertion")
    }
  }

  test("TinyTapeoutTop: End-to-End X25519 Montgomery Ladder Acceleration") {
    simConfig.compile(tt_um_gmlewis_reticulum()).doSim { dut =>
      initDut(dut)

      val scalarHex = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"
      val uHex      = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"
      val expHex    = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"

      val payload = hexToBytes(scalarHex) ++ hexToBytes(uHex)
      assert(payload.length == 64)

      // Dispatch OP_X25519_MULT through TT pins
      ttQspiSendCommand(dut, QspiOpcode.OP_X25519_MULT, payload)
      dut.clockDomain.waitSampling(10)

      // Verify busy signal on uo_out[1]
      assert((dut.io.uo_out.toInt & 0x02) != 0, "uo_out[1] (busy) must be high while X25519 computes")

      // Await IRQ# assertion on uo_out[0]
      var waitCycles = 0
      while ((dut.io.uo_out.toInt & 0x01) != 0 && waitCycles < 5000) {
        dut.clockDomain.waitSampling(10)
        waitCycles += 10
      }
      assert((dut.io.uo_out.toInt & 0x01) == 0, "uo_out[0] (irq_n) must assert low upon completion")

      // Read result via OP_X25519_READ
      dut.io.ui_in #= (dut.io.ui_in.toInt & ~0x02) // CS# = 0
      dut.clockDomain.waitSampling(4)
      ttQspiWriteByte(dut, QspiOpcode.OP_X25519_READ)
      ttQspiWriteByte(dut, 0x00)
      ttQspiWriteByte(dut, 0x00)
      dut.clockDomain.waitSampling(8)

      val result = (0 until 32).map(_ => ttQspiReadByte(dut))
      dut.clockDomain.waitSampling(4)
      dut.io.ui_in #= (dut.io.ui_in.toInt | 0x02) // CS# = 1
      dut.clockDomain.waitSampling(5)

      assert(bytesToHex(result) == expHex, s"X25519 result mismatch: ${bytesToHex(result)} vs $expHex")

      // Clear IRQ
      ttQspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
      dut.clockDomain.waitSampling(10)
      assert((dut.io.uo_out.toInt & 0x01) == 1, "irq_n must return high after OP_IRQ_CLEAR")
    }
  }

  test("TinyTapeoutTop: End-to-End Token Seal & Open Roundtrip") {
    simConfig.compile(tt_um_gmlewis_reticulum()).doSim { dut =>
      initDut(dut)

      val signKey = hexToBytes("0102030405060708090a0b0c0d0e0f10")
      val encKey  = hexToBytes("1112131415161718191a1b1c1d1e1f20")
      val iv      = hexToBytes("2122232425262728292a2b2c2d2e2f30")
      val pt      = "Tiny Tapeout Reticulum Token Test Payload 32B!".getBytes("UTF-8").map(_ & 0xFF).toSeq

      val sealPayload = signKey ++ encKey ++ iv ++ pt

      // Dispatch OP_TOKEN_SEAL
      ttQspiSendCommand(dut, QspiOpcode.OP_TOKEN_SEAL, sealPayload)
      dut.clockDomain.waitSampling(10)

      // Await IRQ
      var waitCycles = 0
      while ((dut.io.uo_out.toInt & 0x01) != 0 && waitCycles < 4000) {
        dut.clockDomain.waitSampling(10)
        waitCycles += 10
      }
      assert((dut.io.uo_out.toInt & 0x01) == 0, "irq_n must assert when Token Seal completes")

      // Read sealed envelope
      dut.io.ui_in #= (dut.io.ui_in.toInt & ~0x02) // CS# = 0
      dut.clockDomain.waitSampling(4)
      ttQspiWriteByte(dut, QspiOpcode.OP_TOKEN_READ)
      ttQspiWriteByte(dut, 0x00)
      ttQspiWriteByte(dut, 0x00)
      dut.clockDomain.waitSampling(8)

      val sealStatus = ttQspiReadByte(dut)
      val sealLenMsb = ttQspiReadByte(dut)
      val sealLenLsb = ttQspiReadByte(dut)
      val sealedLen  = (sealLenMsb << 8) | sealLenLsb

      assert(sealStatus == 0, s"Seal status failed with code $sealStatus")
      val sealedToken = (0 until sealedLen).map(_ => ttQspiReadByte(dut))
      dut.clockDomain.waitSampling(4)
      dut.io.ui_in #= (dut.io.ui_in.toInt | 0x02) // CS# = 1
      dut.clockDomain.waitSampling(5)

      // Clear IRQ
      ttQspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
      dut.clockDomain.waitSampling(10)

      // Now dispatch OP_TOKEN_OPEN with the sealed token (which already contains IV)
      val openPayload = signKey ++ encKey ++ sealedToken
      ttQspiSendCommand(dut, QspiOpcode.OP_TOKEN_OPEN, openPayload)
      dut.clockDomain.waitSampling(10)

      waitCycles = 0
      while ((dut.io.uo_out.toInt & 0x01) != 0 && waitCycles < 4000) {
        dut.clockDomain.waitSampling(10)
        waitCycles += 10
      }
      assert((dut.io.uo_out.toInt & 0x01) == 0, "irq_n must assert when Token Open completes")

      // Read decrypted plaintext
      dut.io.ui_in #= (dut.io.ui_in.toInt & ~0x02) // CS# = 0
      dut.clockDomain.waitSampling(4)
      ttQspiWriteByte(dut, QspiOpcode.OP_TOKEN_READ)
      ttQspiWriteByte(dut, 0x00)
      ttQspiWriteByte(dut, 0x00)
      dut.clockDomain.waitSampling(8)

      val openStatus = ttQspiReadByte(dut)
      val openLenMsb = ttQspiReadByte(dut)
      val openLenLsb = ttQspiReadByte(dut)
      val decryptedLen = (openLenMsb << 8) | openLenLsb

      assert(openStatus == 0, s"Open status failed with code $openStatus")
      assert(decryptedLen == pt.length, s"Decrypted length mismatch: $decryptedLen vs ${pt.length}")

      val decryptedPt = (0 until decryptedLen).map(_ => ttQspiReadByte(dut))
      dut.clockDomain.waitSampling(4)
      dut.io.ui_in #= (dut.io.ui_in.toInt | 0x02) // CS# = 1
      dut.clockDomain.waitSampling(5)

      assert(decryptedPt == pt, "Decrypted plaintext does not match original plaintext!")

      ttQspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
      dut.clockDomain.waitSampling(10)
      assert((dut.io.uo_out.toInt & 0x01) == 1, "irq_n must return high after final OP_IRQ_CLEAR")
    }
  }
}
