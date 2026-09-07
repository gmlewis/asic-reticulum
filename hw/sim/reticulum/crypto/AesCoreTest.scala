package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class AesCoreTest extends AnyFunSuite {

  def hexToBigIntBE(hex: String): BigInt = BigInt(hex, 16)

  def bigIntToHexBE(bi: BigInt, numBytes: Int = 16): String = {
    val s = bi.toString(16)
    val pad = "0" * math.max(0, numBytes * 2 - s.length)
    pad + s
  }

  test("AesCore: FIPS 197 Key Expansion, Encrypt and Decrypt") {
    SimConfig.compile(AesCore()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.key #= 0
      dut.io.blockIn #= 0
      dut.io.enc #= true
      dut.io.loadKey #= false
      dut.io.start #= false
      dut.clockDomain.waitSampling(5)

      // FIPS 197 Appendix B Test Vector
      val keyHex = "000102030405060708090a0b0c0d0e0f"
      val ptHex  = "00112233445566778899aabbccddeeff"
      val ctHex  = "69c4e0d86a7b0430d8cdb78070b4c55a"

      // 1. Load Key
      dut.io.key #= hexToBigIntBE(keyHex)
      dut.io.loadKey #= true
      dut.clockDomain.waitSampling()
      dut.io.loadKey #= false
      dut.clockDomain.waitSampling()

      // Wait for key expansion (10 cycles)
      dut.clockDomain.waitSamplingWhere(dut.io.keyReady.toBoolean)
      assert(!dut.io.busy.toBoolean, "Dut should be idle after key expansion")

      // 2. Encrypt
      dut.io.blockIn #= hexToBigIntBE(ptHex)
      dut.io.enc #= true
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      dut.clockDomain.waitSampling()

      assert(dut.io.busy.toBoolean, "Dut should be busy during encryption")

      // Wait for completion (10 cycles)
      dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)
      val actualCt = bigIntToHexBE(dut.io.blockOut.toBigInt)
      assert(actualCt == ctHex, s"FIPS 197 Encrypt mismatch: expected $ctHex, got $actualCt")

      dut.clockDomain.waitSampling()
      assert(!dut.io.busy.toBoolean, "Dut should be idle after encryption")

      // 3. Decrypt
      dut.io.blockIn #= hexToBigIntBE(ctHex)
      dut.io.enc #= false
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      dut.clockDomain.waitSampling()

      assert(dut.io.busy.toBoolean, "Dut should be busy during decryption")

      // Wait for completion (10 cycles)
      dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)
      val actualPt = bigIntToHexBE(dut.io.blockOut.toBigInt)
      assert(actualPt == ptHex, s"FIPS 197 Decrypt mismatch: expected $ptHex, got $actualPt")
    }
  }

  test("AesCore: NIST SP 800-38A Test Vector") {
    SimConfig.compile(AesCore()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.key #= 0
      dut.io.blockIn #= 0
      dut.io.enc #= true
      dut.io.loadKey #= false
      dut.io.start #= false
      dut.clockDomain.waitSampling(5)

      // NIST SP 800-38A Section F.1.1
      val keyHex = "2b7e151628aed2a6abf7158809cf4f3c"
      val ptHex  = "6bc1bee22e409f96e93d7e117393172a"
      val ctHex  = "3ad77bb40d7a3660a89ecaf32466ef97"

      // Load Key
      dut.io.key #= hexToBigIntBE(keyHex)
      dut.io.loadKey #= true
      dut.clockDomain.waitSampling()
      dut.io.loadKey #= false
      dut.clockDomain.waitSamplingWhere(dut.io.keyReady.toBoolean)

      // Encrypt
      dut.io.blockIn #= hexToBigIntBE(ptHex)
      dut.io.enc #= true
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)
      val actualCt = bigIntToHexBE(dut.io.blockOut.toBigInt)
      assert(actualCt == ctHex, s"SP 800-38A Encrypt mismatch: expected $ctHex, got $actualCt")

      // Decrypt
      dut.clockDomain.waitSampling()
      dut.io.blockIn #= hexToBigIntBE(ctHex)
      dut.io.enc #= false
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)
      val actualPt = bigIntToHexBE(dut.io.blockOut.toBigInt)
      assert(actualPt == ptHex, s"SP 800-38A Decrypt mismatch: expected $ptHex, got $actualPt")
    }
  }
}
