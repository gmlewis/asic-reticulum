package reticulum.fpga

import org.scalatest.funsuite.AnyFunSuite
import reticulum.bus.QspiOpcode
import spinal.core._
import spinal.core.sim._

class FpgaTopTest extends AnyFunSuite {

  def hexToBytes(hex: String): Array[Byte] = {
    hex.sliding(2, 2).toArray.map(s => Integer.parseInt(s, 16).toByte)
  }

  def bytesToHex(bytes: Seq[Int]): String = {
    bytes.map(b => "%02x".format(b & 0xFF)).mkString
  }

  def qspiWriteByte(dut: FpgaTop, byteVal: Int): Unit = {
    val highNibble = (byteVal >> 4) & 0x0F
    val lowNibble  = byteVal & 0x0F

    // Cycle 1: High nibble
    dut.io.qspi_data_in #= highNibble
    dut.clockDomain.waitSampling(2)
    dut.io.qspi_sclk #= true
    dut.clockDomain.waitSampling(2)
    dut.io.qspi_sclk #= false

    // Cycle 2: Low nibble
    dut.io.qspi_data_in #= lowNibble
    dut.clockDomain.waitSampling(2)
    dut.io.qspi_sclk #= true
    dut.clockDomain.waitSampling(2)
    dut.io.qspi_sclk #= false
  }

  def qspiReadByte(dut: FpgaTop): Int = {
    // Cycle 1: High nibble
    dut.clockDomain.waitSampling(4)
    dut.io.qspi_sclk #= true
    dut.clockDomain.waitSampling(2)
    val highNibble = dut.io.qspi_data_out.toInt
    dut.clockDomain.waitSampling(2)
    dut.io.qspi_sclk #= false

    // Cycle 2: Low nibble
    dut.clockDomain.waitSampling(4)
    dut.io.qspi_sclk #= true
    dut.clockDomain.waitSampling(2)
    val lowNibble = dut.io.qspi_data_out.toInt
    dut.clockDomain.waitSampling(2)
    dut.io.qspi_sclk #= false

    (highNibble << 4) | lowNibble
  }

  def qspiSendCommand(dut: FpgaTop, opcode: Int, payload: Seq[Int] = Seq()): Unit = {
    val lenMsb = (payload.length >> 8) & 0xFF
    val lenLsb = payload.length & 0xFF

    dut.io.qspi_cs_n #= false
    dut.clockDomain.waitSampling(4)

    qspiWriteByte(dut, opcode)
    qspiWriteByte(dut, lenMsb)
    qspiWriteByte(dut, lenLsb)
    for (b <- payload) {
      qspiWriteByte(dut, b)
    }

    dut.clockDomain.waitSampling(4)
    dut.io.qspi_cs_n #= true
    dut.clockDomain.waitSampling(5)
  }

  def initDut(dut: FpgaTop): Unit = {
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.qspi_sclk    #= false
    dut.io.qspi_cs_n    #= true
    dut.io.qspi_data_in #= 0
    dut.clockDomain.waitSampling(10)
  }

  test("FpgaTop: Heartbeat and Diagnostic LEDs Verification") {
    SimConfig.compile(FpgaTop(numEngines = 4, roundsPerStage = 1)).doSim { dut =>
      initDut(dut)

      assert(dut.io.qspi_irq_n.toBoolean, "irq_n should start high (inactive)")
      assert(!dut.io.led_irq.toBoolean, "led_irq should start off")
      assert(!dut.io.led_busy.toBoolean, "led_busy should start off")

      // Allow heartbeat counter to increment
      dut.clockDomain.waitSampling(50)

      // Query status via OP_STATUS
      dut.io.qspi_cs_n #= false
      dut.clockDomain.waitSampling(4)
      qspiWriteByte(dut, QspiOpcode.OP_STATUS)
      qspiWriteByte(dut, 0x00)
      qspiWriteByte(dut, 0x00)
      dut.clockDomain.waitSampling(8)

      assert(dut.io.qspi_data_oe.toBoolean, "qspi_data_oe should assert during read response")

      val status = qspiReadByte(dut)
      val ver    = qspiReadByte(dut)
      val rH     = qspiReadByte(dut)
      val rL     = qspiReadByte(dut)

      dut.io.qspi_cs_n #= true
      dut.clockDomain.waitSampling(5)

      assert(!dut.io.qspi_data_oe.toBoolean, "qspi_data_oe should deassert after CS goes high")
      assert(ver == 0x10, s"Expected version 0x10, got 0x${ver.toHexString}")
      assert(status == 0x00, s"Expected idle status 0x00, got 0x${status.toHexString}")
    }
  }

  test("FpgaTop: Tri-State Bus Turnaround and QSPI Protocol Verification") {
    SimConfig.compile(FpgaTop(numEngines = 4, roundsPerStage = 1)).doSim { dut =>
      initDut(dut)

      // Verify that during host writes, data_oe is FALSE (Hi-Z input mode)
      dut.io.qspi_cs_n #= false
      dut.clockDomain.waitSampling(4)
      assert(!dut.io.qspi_data_oe.toBoolean, "data_oe must be false during command write")

      qspiWriteByte(dut, QspiOpcode.OP_STATUS)
      qspiWriteByte(dut, 0x00)
      qspiWriteByte(dut, 0x00)
      dut.clockDomain.waitSampling(8)

      // During read, data_oe asserts TRUE
      assert(dut.io.qspi_data_oe.toBoolean, "data_oe must assert during status readout")
      val status = qspiReadByte(dut)
      val ver    = qspiReadByte(dut)
      val rH     = qspiReadByte(dut)
      val rL     = qspiReadByte(dut)

      dut.io.qspi_cs_n #= true
      dut.clockDomain.waitSampling(5)

      // When CS deasserts, data_oe immediately releases to FALSE
      assert(!dut.io.qspi_data_oe.toBoolean, "data_oe must release immediately on CS deassertion")
    }
  }

  test("FpgaTop: Full Concurrency Stress Test (Stamper + X25519 + 4-Way Token Pool)") {
    SimConfig.compile(FpgaTop(numEngines = 4, roundsPerStage = 1)).doSim { dut =>
      initDut(dut)

      // 1. Launch a background Stamp grind on Stamper
      val targetCost      = 12
      val midstateBytes   = Seq.fill(32)(0x11)
      val baseCandBytes   = Seq.fill(32)(0x22)
      val totalLenBytes   = Seq(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x60)
      val startNonceBytes = Seq(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val maxRoundsBytes  = Seq(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0f, 0x00)
      val stampPayload    = Seq(targetCost) ++ midstateBytes ++ baseCandBytes ++ totalLenBytes ++ startNonceBytes ++ maxRoundsBytes

      qspiSendCommand(dut, QspiOpcode.OP_STAMP_GRIND, stampPayload)
      dut.clockDomain.waitSampling(20)

      assert(dut.io.led_busy.toBoolean, "led_busy should be active during stamp grinding")

      // 2. While Stamper is grinding, launch X25519 scalar multiplication
      val scalarHex = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"
      val uHex      = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"
      val expXHex   = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"
      val xPayload  = hexToBytes(scalarHex).map(_ & 0xFF).toSeq ++ hexToBytes(uHex).map(_ & 0xFF).toSeq
      assert(xPayload.length == 64)

      qspiSendCommand(dut, QspiOpcode.OP_X25519_MULT, xPayload)
      dut.clockDomain.waitSampling(20)

      // 3. While both Stamper and X25519 are computing, dispatch 4 parallel Token Seal jobs
      val numTokenJobs = 4
      val tokenKeys = (0 until numTokenJobs).map { i =>
        val signKey = hexToBytes(f"a0a1a2a3a4a5a6a7a8a9aaabacadae${i}%02xaf")
        val encKey  = hexToBytes(f"b0b1b2b3b4b5b6b7b8b9babbbcbdbe${i}%02xbf")
        val iv      = hexToBytes(f"c0c1c2c3c4c5c6c7c8c9cacbcccdce${i}%02xcf")
        val pt      = f"FpgaTop Concurrency Job #$i%03d-32B".getBytes("UTF-8")
        assert(pt.length == 32)
        (signKey, encKey, iv, pt)
      }

      for (i <- 0 until numTokenJobs) {
        val (signKey, encKey, iv, pt) = tokenKeys(i)
        val tokenPayload = (signKey ++ encKey ++ iv ++ pt).map(_ & 0xFF).toSeq
        qspiSendCommand(dut, QspiOpcode.OP_TOKEN_SEAL, tokenPayload)
        dut.clockDomain.waitSampling(10)
      }

      // 4. Check status via OP_STATUS: all subsystems are actively executing
      dut.io.qspi_cs_n #= false
      dut.clockDomain.waitSampling(4)
      qspiWriteByte(dut, QspiOpcode.OP_STATUS)
      qspiWriteByte(dut, 0x00)
      qspiWriteByte(dut, 0x00)
      dut.clockDomain.waitSampling(8)

      val liveStatus  = qspiReadByte(dut)
      val statusByte1 = qspiReadByte(dut)
      qspiReadByte(dut)
      qspiReadByte(dut)
      dut.io.qspi_cs_n #= true
      dut.clockDomain.waitSampling(5)

      val s30 = liveStatus & 0x30
      assert((liveStatus & 0x05) != 0, s"Stamper should be active or completed, got 0x$liveStatus%02x")
      // X25519: actively computing (bit 5) or already completed (bit 4)
      assert(s30 != 0, s"X25519 should be active or completed, got 0x$liveStatus%02x")
      // Token: either has finished envelopes (bit 7) or active engines (byte 1 bit 1) or completed (byte 1 bit 0)
      assert((liveStatus & 0x80) != 0 || (statusByte1 & 0x03) != 0, "Token pool should be active or have completed jobs")

      // 5. Read back completed Token Seal envelopes as each finishes in FIFO order
      val sealedTokens = collection.mutable.ArrayBuffer[Seq[Int]]()
      for (i <- 0 until numTokenJobs) {
        // Wait for next token job to be done
        var waitCycles = 0
        while (dut.io.qspi_irq_n.toBoolean && waitCycles < 4000) {
          dut.clockDomain.waitSampling(10)
          waitCycles += 10
        }
        assert(!dut.io.qspi_irq_n.toBoolean, s"irq_n should assert when Token job $i finishes")
        assert(dut.io.led_irq.toBoolean, "led_irq should illuminate when interrupt asserts")

        // Read result via OP_TOKEN_READ
        dut.io.qspi_cs_n #= false
        dut.clockDomain.waitSampling(4)
        qspiWriteByte(dut, QspiOpcode.OP_TOKEN_READ)
        qspiWriteByte(dut, 0x00)
        qspiWriteByte(dut, 0x00)
        dut.clockDomain.waitSampling(8)

        val sealStatus = qspiReadByte(dut)
        val sealLenMsb = qspiReadByte(dut)
        val sealLenLsb = qspiReadByte(dut)
        val sealedLen  = (sealLenMsb << 8) | sealLenLsb

        assert(sealStatus == 0, s"Expected OK status (0) for Token job $i, got $sealStatus")
        assert(sealedLen == 96, s"Expected sealed len 96 for Token job $i, got $sealedLen")

        val tokenBytes = (0 until sealedLen).map(_ => qspiReadByte(dut))
        sealedTokens += tokenBytes

        dut.io.qspi_cs_n #= true
        dut.clockDomain.waitSampling(5)

        // Clear IRQ to advance completion queue
        qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
        dut.clockDomain.waitSampling(5)
      }

      // 6. Verify X25519 scalar multiplication result
      // If not yet done, wait for interrupt
      if ((liveStatus & 0x10) == 0) {
        var waitCycles = 0
        while (dut.io.qspi_irq_n.toBoolean && waitCycles < 5000) {
          dut.clockDomain.waitSampling(10)
          waitCycles += 10
        }
      }

      // Read X25519 result via OP_X25519_READ
      dut.io.qspi_cs_n #= false
      dut.clockDomain.waitSampling(4)
      qspiWriteByte(dut, QspiOpcode.OP_X25519_READ)
      qspiWriteByte(dut, 0x00)
      qspiWriteByte(dut, 0x00)
      dut.clockDomain.waitSampling(8)

      val xResult = (0 until 32).map(_ => qspiReadByte(dut))
      dut.io.qspi_cs_n #= true
      dut.clockDomain.waitSampling(5)

      assert(bytesToHex(xResult) == expXHex, s"X25519 result mismatch: got ${bytesToHex(xResult)}, exp $expXHex")

      // Clear X25519 IRQ
      qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
      dut.clockDomain.waitSampling(5)

      // 7. Abort Stamper background grind cleanly
      qspiSendCommand(dut, QspiOpcode.OP_ABORT)
      dut.clockDomain.waitSampling(10)

      qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
      dut.clockDomain.waitSampling(5)
      assert(dut.io.qspi_irq_n.toBoolean, "irq_n should return high after all jobs cleared")
    }
  }
}
