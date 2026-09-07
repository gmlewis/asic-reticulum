package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class X25519LadderTest extends AnyFunSuite {

  def hexToBigInt(hex: String): BigInt = {
    // 32-byte hex string in little-endian byte order
    val bytes = hex.sliding(2, 2).toArray.map(s => Integer.parseInt(s, 16).toByte)
    var bi = BigInt(0)
    for (i <- 0 until 32) {
      bi |= (BigInt(bytes(i) & 0xFF) << (i * 8))
    }
    bi
  }

  def bigIntToHex(bi: BigInt): String = {
    val sb = new StringBuilder
    for (i <- 0 until 32) {
      val b = (bi >> (i * 8)) & 0xFF
      sb.append(f"$b%02x")
    }
    sb.toString()
  }

  test("X25519Ladder: RFC 7748 Vector 1") {
    SimConfig.compile(X25519Ladder()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.io.scalar #= 0
      dut.io.uCoord #= 0
      dut.clockDomain.waitSampling(5)

      // RFC 7748 Section 5.2 Vector 1
      val scalarHex = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"
      val uHex      = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"
      val expHex    = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"

      dut.io.scalar #= hexToBigInt(scalarHex)
      dut.io.uCoord #= hexToBigInt(uHex)
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      dut.clockDomain.waitSampling()

      assert(dut.io.busy.toBoolean, "Dut should be busy after start")

      // Wait for completion (expected ~4,350 cycles)
      dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)
      assert(!dut.io.busy.toBoolean, "Dut should not be busy when done")
      assert(dut.io.irq.toBoolean, "IRQ should be asserted")

      val actual = bigIntToHex(dut.io.result.toBigInt)
      assert(actual == expHex, f"RFC 7748 Vector 1 mismatch: expected $expHex, got $actual")

      // Test IRQ clear
      dut.io.irqClear #= true
      dut.clockDomain.waitSampling()
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling()
      assert(!dut.io.irq.toBoolean, "IRQ should be cleared after irqClear pulse")
    }
  }

  test("X25519Ladder: RFC 7748 Vector 2") {
    SimConfig.compile(X25519Ladder()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.io.scalar #= 0
      dut.io.uCoord #= 0
      dut.clockDomain.waitSampling(5)

      // RFC 7748 Section 5.2 Vector 2 (Alice & Bob)
      val scalarHex = "4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"
      val uHex      = "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"
      val expHex    = "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"

      dut.io.scalar #= hexToBigInt(scalarHex)
      dut.io.uCoord #= hexToBigInt(uHex)
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      dut.clockDomain.waitSampling()

      assert(dut.io.busy.toBoolean, "Dut should be busy after start")

      dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)
      val actual = bigIntToHex(dut.io.result.toBigInt)
      assert(actual == expHex, f"RFC 7748 Vector 2 mismatch: expected $expHex, got $actual")
    }
  }

  test("X25519Ladder: host abort resets engine to idle") {
    SimConfig.compile(X25519Ladder()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.io.scalar #= 12345
      dut.io.uCoord #= 9
      dut.clockDomain.waitSampling(5)

      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      dut.clockDomain.waitSampling()

      assert(dut.io.busy.toBoolean, "Dut should be busy before abort")

      // Let it run for 100 cycles then abort
      dut.clockDomain.waitSampling(100)
      assert(dut.io.busy.toBoolean, "Dut should still be busy before abort")

      dut.io.abort #= true
      dut.clockDomain.waitSampling()
      dut.io.abort #= false
      dut.clockDomain.waitSampling()

      assert(!dut.io.busy.toBoolean, "Dut should be idle immediately after abort")
      assert(!dut.io.done.toBoolean, "Dut should not signal done after abort")
      assert(!dut.io.irq.toBoolean, "Dut should not assert IRQ after abort")
    }
  }
}
