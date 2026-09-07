package reticulum.bus

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class QspiTopTest extends AnyFunSuite {

  // Reference test vectors from StamperTest (Target 2 winner at offset 6)
  val midstateHex = "ae1cd45355c7b9063b467d57d0473f5d9313bf00880190a9505c03cf569d934a"
  val baseCandidateHex = "1010101010101010101010101010101010101010101010101010101010101010"
  val totalLenBits = 768L

  def hexToBytes(hex: String): Seq[Int] = {
    hex.grouped(2).map(Integer.parseInt(_, 16)).toSeq
  }

  def bytesToHex(bytes: Seq[Int]): String = {
    bytes.map(b => f"$b%02x").mkString
  }

  /**
   * Helper: Simulates host sending one byte over 4-bit QSPI (Mode 0).
   */
  def qspiWriteByte(dut: QspiTop, byteVal: Int): Unit = {
    val highNibble = (byteVal >> 4) & 0x0F
    val lowNibble  = byteVal & 0x0F

    // Cycle 1: High nibble
    dut.io.data_in #= highNibble
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false

    // Cycle 2: Low nibble
    dut.io.data_in #= lowNibble
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false
  }

  /**
   * Helper: Simulates host reading one byte over 4-bit QSPI (Mode 0).
   */
  def qspiReadByte(dut: QspiTop): Int = {
    // Cycle 1: High nibble
    dut.clockDomain.waitSampling(4)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    val highNibble = dut.io.data_out.toInt
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false

    // Cycle 2: Low nibble
    dut.clockDomain.waitSampling(4)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    val lowNibble = dut.io.data_out.toInt
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false

    (highNibble << 4) | lowNibble
  }

  /**
   * Helper: Sends a full command frame over QSPI.
   */
  def qspiSendCommand(dut: QspiTop, opcode: Int, payload: Seq[Int] = Seq()): Unit = {
    val lenMsb = (payload.length >> 8) & 0xFF
    val lenLsb = payload.length & 0xFF

    dut.io.cs_n #= false
    dut.clockDomain.waitSampling(4)

    qspiWriteByte(dut, opcode)
    qspiWriteByte(dut, lenMsb)
    qspiWriteByte(dut, lenLsb)
    for (b <- payload) {
      qspiWriteByte(dut, b)
    }

    dut.clockDomain.waitSampling(4)
    dut.io.cs_n #= true
    dut.clockDomain.waitSampling(4)
  }

  test("QspiTop: End-to-end stamp grinding, hardware IRQ, and result readout over QSPI") {
    SimConfig.compile(QspiTop(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.sclk #= false
      dut.io.cs_n #= true
      dut.io.data_in #= 0
      dut.clockDomain.waitSampling(5)

      assert(dut.io.irq_n.toBoolean, "irq_n should start high (inactive)")
      assert(!dut.io.data_oe.toBoolean, "data_oe should start low")

      // 1. Host dispatches OP_STAMP_GRIND over QSPI
      val targetCost = 2
      val midstateBytes = hexToBytes(midstateHex)
      val baseCandBytes = hexToBytes(baseCandidateHex)
      val totalLenBytes = Seq(
        ((totalLenBits >> 56) & 0xFF).toInt,
        ((totalLenBits >> 48) & 0xFF).toInt,
        ((totalLenBits >> 40) & 0xFF).toInt,
        ((totalLenBits >> 32) & 0xFF).toInt,
        ((totalLenBits >> 24) & 0xFF).toInt,
        ((totalLenBits >> 16) & 0xFF).toInt,
        ((totalLenBits >> 8) & 0xFF).toInt,
        (totalLenBits & 0xFF).toInt
      )
      val startNonceBytes = Seq(0, 0, 0, 0, 0, 0, 0, 0)
      val maxRoundsBytes  = Seq(0, 0, 0, 0, 0, 0, 0, 0)

      val payload = Seq(targetCost) ++ midstateBytes ++ baseCandBytes ++ totalLenBytes ++ startNonceBytes ++ maxRoundsBytes
      assert(payload.length == 89)

      qspiSendCommand(dut, QspiOpcode.OP_STAMP_GRIND, payload)

      // 2. Host yields; wait for hardware interrupt (irq_n goes low)
      var cycles = 0
      while (dut.io.irq_n.toBoolean && cycles < 500) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      assert(!dut.io.irq_n.toBoolean, s"Hardware interrupt irq_n did not assert low (cycles=$cycles)")

      // 3. Host initiates read transaction: OP_STAMP_READ
      dut.io.cs_n #= false
      dut.clockDomain.waitSampling(4)

      qspiWriteByte(dut, QspiOpcode.OP_STAMP_READ)
      qspiWriteByte(dut, 0x00) // len MSB
      qspiWriteByte(dut, 0x00) // len LSB
      dut.clockDomain.waitSampling(8)

      // Read back 82 result bytes over QSPI
      val readBytes = collection.mutable.ArrayBuffer[Int]()
      for (_ <- 0 until 82) {
        readBytes += qspiReadByte(dut)
      }

      dut.clockDomain.waitSampling(4)
      dut.io.cs_n #= true
      dut.clockDomain.waitSampling(4)

      println(s"DEBUG: readBytes (${readBytes.length}) = ${readBytes.map(b => f"0x$b%02x").mkString(", ")}")
      assert(readBytes.length == 82)
      // Byte 0: status flag (0x0B: done=1, meetsTarget=1, busy=0, irq=1)
      assert(readBytes(0) == 0x0B, f"Status byte mismatch: 0x${readBytes(0)}%02x")
      // Byte 1: winning zeros
      assert(readBytes(1) == 2, s"Expected 2 leading zeros, got ${readBytes(1)}")
      // Bytes 2..9: winning nonce
      val gotNonce = readBytes.slice(2, 10).foldLeft(0L)((acc, b) => (acc << 8) | b)
      assert(gotNonce == 6L, s"Expected winning nonce 6, got $gotNonce")
      // Bytes 10..17: rounds evaluated
      val gotRounds = readBytes.slice(10, 18).foldLeft(0L)((acc, b) => (acc << 8) | b)
      assert(gotRounds == 7L, s"Expected 7 rounds evaluated, got $gotRounds")
      // Bytes 18..49: winning digest
      val gotDigestHex = bytesToHex(readBytes.slice(18, 50).toSeq)
      assert(
        gotDigestHex == "23816ce4cb8a3b3198979512944ecba9ae59d33461b72bb6509ce1f16d5c0d6f",
        s"Digest mismatch: got $gotDigestHex"
      )

      // 4. Host clears IRQ via OP_IRQ_CLEAR
      qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
      dut.clockDomain.waitSampling(5)

      assert(dut.io.irq_n.toBoolean, "irq_n should return high (inactive) after OP_IRQ_CLEAR")
    }
  }

  test("QspiTop: Status polling and host abort over QSPI") {
    SimConfig.compile(QspiTop(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.sclk #= false
      dut.io.cs_n #= true
      dut.io.data_in #= 0
      dut.clockDomain.waitSampling(5)

      // Launch search with difficulty 24 (unreachable in short cycles)
      val targetCost = 24
      val midstateBytes = hexToBytes(midstateHex)
      val baseCandBytes = hexToBytes(baseCandidateHex)
      val totalLenBytes = Seq(0, 0, 0, 0, 0, 0, 3, 0) // 768
      val startNonceBytes = Seq(0, 0, 0, 0, 0, 0, 0, 0)
      val maxRoundsBytes  = Seq(0, 0, 0, 0, 0, 0, 0, 0)

      val payload = Seq(targetCost) ++ midstateBytes ++ baseCandBytes ++ totalLenBytes ++ startNonceBytes ++ maxRoundsBytes
      qspiSendCommand(dut, QspiOpcode.OP_STAMP_GRIND, payload)

      // Allow grinding for 30 cycles
      dut.clockDomain.waitSampling(30)
      assert(dut.io.irq_n.toBoolean, "irq_n should still be high while grinding")

      // Check status via OP_STATUS
      dut.io.cs_n #= false
      dut.clockDomain.waitSampling(4)
      qspiWriteByte(dut, QspiOpcode.OP_STATUS)
      qspiWriteByte(dut, 0x00)
      qspiWriteByte(dut, 0x00)
      dut.clockDomain.waitSampling(8)

      val status1 = qspiReadByte(dut)
      val ver1    = qspiReadByte(dut)
      val rH1     = qspiReadByte(dut)
      val rL1     = qspiReadByte(dut)
      dut.io.cs_n #= true
      dut.clockDomain.waitSampling(5)

      // In grinding state: busy=1 (bit 2), done=0, meetsTarget=0, irq=0 -> status = 0x04
      assert((status1 & 0x04) != 0, "busy bit should be set in OP_STATUS during grinding")
      assert(ver1 == 0x10, "Version should be 0x10")

      // Abort via OP_ABORT
      qspiSendCommand(dut, QspiOpcode.OP_ABORT)
      dut.clockDomain.waitSampling(10)

      // Verify irq_n asserted on abort
      assert(!dut.io.irq_n.toBoolean, "irq_n should assert low on abort")

      // Clear IRQ
      qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
      dut.clockDomain.waitSampling(5)
      assert(dut.io.irq_n.toBoolean, "irq_n should return high after OP_IRQ_CLEAR")
    }
  }

  test("QspiTop: End-to-end X25519 scalar multiplication, hardware IRQ, and result readout over QSPI") {
    SimConfig.compile(QspiTop(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.sclk #= false
      dut.io.cs_n #= true
      dut.io.data_in #= 0
      dut.clockDomain.waitSampling(5)

      assert(dut.io.irq_n.toBoolean, "irq_n should start high (inactive)")

      // RFC 7748 Vector 1
      val scalarHex = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"
      val uHex      = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"
      val expHex    = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"

      val scalarBytes = hexToBytes(scalarHex)
      val uBytes      = hexToBytes(uHex)
      val payload     = scalarBytes ++ uBytes
      assert(payload.length == 64)

      // 1. Host dispatches OP_X25519_MULT over 4-bit QSPI
      qspiSendCommand(dut, QspiOpcode.OP_X25519_MULT, payload)

      // 2. Host waits for hardware interrupt (irq_n going low)
      var waitCycles = 0
      val maxWait    = 6000
      while (dut.io.irq_n.toBoolean && waitCycles < maxWait) {
        dut.clockDomain.waitSampling(10)
        waitCycles += 10
      }

      assert(!dut.io.irq_n.toBoolean, s"irq_n was not asserted low within $maxWait cycles")

      // 3. Host reads back 32-byte result via OP_X25519_READ
      dut.io.cs_n #= false
      dut.clockDomain.waitSampling(4)

      qspiWriteByte(dut, QspiOpcode.OP_X25519_READ)
      qspiWriteByte(dut, 0x00)
      qspiWriteByte(dut, 0x00)

      dut.clockDomain.waitSampling(8)

      val resultBytes = collection.mutable.ArrayBuffer[Int]()
      for (_ <- 0 until 32) {
        resultBytes += qspiReadByte(dut)
      }

      dut.clockDomain.waitSampling(4)
      dut.io.cs_n #= true
      dut.clockDomain.waitSampling(5)

      val gotHex = bytesToHex(resultBytes.toSeq)
      assert(gotHex == expHex, f"X25519 QSPI result mismatch: expected $expHex, got $gotHex")

      // 4. Host clears interrupt via OP_IRQ_CLEAR
      qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
      dut.clockDomain.waitSampling(5)

      assert(dut.io.irq_n.toBoolean, "irq_n should return high (inactive) after OP_IRQ_CLEAR")
    }
  }
}
