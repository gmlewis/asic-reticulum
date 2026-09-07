package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class TokenEngineTest extends AnyFunSuite {

  def hexToBytes(hex: String): Array[Byte] = {
    hex.sliding(2, 2).map(Integer.parseInt(_, 16).toByte).toArray
  }

  def bytesToHex(bytes: Array[Byte]): String = {
    bytes.map("%02x".format(_)).mkString
  }

  def writeHostMem(dut: TokenEngine, offset: Int, data: Array[Byte]): Unit = {
    dut.io.hostWrEn #= true
    for (i <- data.indices) {
      dut.io.hostWrAddr #= offset + i
      dut.io.hostWrData #= (data(i).toInt & 0xff)
      dut.clockDomain.waitSampling()
    }
    dut.io.hostWrEn #= false
    dut.clockDomain.waitSampling()
  }

  def readHostMem(dut: TokenEngine, offset: Int, len: Int): Array[Byte] = {
    val res = new Array[Byte](len)
    for (i <- 0 until len) {
      dut.io.hostRdAddr #= offset + i
      dut.clockDomain.waitSampling()
      res(i) = dut.io.hostRdData.toBigInt.toByte
    }
    res
  }

  def initDut(dut: TokenEngine): Unit = {
    dut.clockDomain.forkStimulus(10)
    dut.io.start #= false
    dut.io.mode #= true
    dut.io.abort #= false
    dut.io.irqClear #= false
    dut.io.signKey #= 0
    dut.io.encKey #= 0
    dut.io.iv #= 0
    dut.io.dataLen #= 0
    dut.io.hostWrEn #= false
    dut.io.hostWrAddr #= 0
    dut.io.hostWrData #= 0
    dut.io.hostRdAddr #= 0
    dut.clockDomain.waitSampling(5)
  }

  def startOp(dut: TokenEngine, mode: Boolean, dataLen: Int): Unit = {
    dut.io.mode    #= mode
    dut.io.dataLen #= dataLen
    dut.io.start   #= true
    dut.clockDomain.waitSampling()
    dut.io.start   #= false
    dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
    dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)
  }

  test("TokenEngine: Seal and Open 32-byte Plaintext Roundtrip") {
    SimConfig.compile(TokenEngine()).doSim { dut =>
      initDut(dut)

      val signKeyHex = "0102030405060708090a0b0c0d0e0f10"
      val encKeyHex  = "1112131415161718191a1b1c1d1e1f20"
      val ivHex      = "2122232425262728292a2b2c2d2e2f30"
      val plaintext  = "Reticulum Token Engine Test 1234".getBytes("UTF-8")
      assert(plaintext.length == 32)

      // 1. Write Plaintext to mem[16 .. 47]
      writeHostMem(dut, 16, plaintext)

      // 2. Start Seal
      dut.io.signKey #= BigInt(signKeyHex, 16)
      dut.io.encKey  #= BigInt(encKeyHex, 16)
      dut.io.iv      #= BigInt(ivHex, 16)
      startOp(dut, mode = true, plaintext.length)

      assert(dut.io.status.toBigInt == 0, s"Seal status should be OK (0), got ${dut.io.status.toBigInt}")
      assert(dut.io.irq.toBoolean, "IRQ should be asserted")
      val sealedLen = dut.io.resultLen.toInt
      assert(sealedLen == 96, s"Expected sealed length 96 (16 IV + 48 CT + 32 HMAC), got $sealedLen")
      assert(dut.io.resultOffset.toInt == 0, "Result offset should be 0")

      // Clear IRQ
      dut.io.irqClear #= true
      dut.clockDomain.waitSampling()
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling()
      assert(!dut.io.irq.toBoolean, "IRQ should clear")

      // 3. Read sealed token from mem[0 .. 95]
      val sealedToken = readHostMem(dut, 0, sealedLen)
      val ivRecovered = sealedToken.slice(0, 16)
      assert(bytesToHex(ivRecovered) == ivHex, "Token IV must match input IV")

      // 4. Open the sealed token
      // The token is already in mem[0 .. 95]
      startOp(dut, mode = false, sealedLen)

      assert(dut.io.status.toBigInt == 0, s"Open status should be OK (0), got ${dut.io.status.toBigInt}")
      assert(dut.io.irq.toBoolean, "IRQ should be asserted")
      val openedLen = dut.io.resultLen.toInt
      assert(openedLen == 32, s"Expected opened length 32, got $openedLen")
      assert(dut.io.resultOffset.toInt == 16, "Result offset should be 16")

      // 5. Read decrypted plaintext from mem[16 .. 47]
      val recoveredPt = readHostMem(dut, 16, openedLen)
      assert(new String(recoveredPt, "UTF-8") == "Reticulum Token Engine Test 1234",
        s"Plaintext mismatch: got ${new String(recoveredPt, "UTF-8")}")
    }
  }

  test("TokenEngine: Variable Payload Lengths (Empty, 1B, 15B, 16B, 80B)") {
    SimConfig.compile(TokenEngine()).doSim { dut =>
      initDut(dut)

      val signKeyHex = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
      val encKeyHex  = "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
      val ivHex      = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf"

      val testPayloads = Seq(
        Array.emptyByteArray,
        "A".getBytes("UTF-8"),
        "123456789012345".getBytes("UTF-8"), // 15 bytes
        "1234567890123456".getBytes("UTF-8"), // 16 bytes
        ("Reticulum Mesh Radio Network Packet Broadcast Test " +
         "Multi-Block 80 Bytes Payload Test!").getBytes("UTF-8") // 84 bytes
      )

      dut.io.signKey #= BigInt(signKeyHex, 16)
      dut.io.encKey  #= BigInt(encKeyHex, 16)
      dut.io.iv      #= BigInt(ivHex, 16)

      for (pt <- testPayloads) {
        if (pt.length > 0) {
          writeHostMem(dut, 16, pt)
        }

        startOp(dut, mode = true, pt.length)
        assert(dut.io.status.toBigInt == 0, s"Seal failed for len ${pt.length}")
        val sealedLen = dut.io.resultLen.toInt

        // Open
        startOp(dut, mode = false, sealedLen)
        assert(dut.io.status.toBigInt == 0, s"Open failed for len ${pt.length}")
        val recoveredLen = dut.io.resultLen.toInt
        assert(recoveredLen == pt.length, s"Length mismatch: exp ${pt.length}, got $recoveredLen")

        if (pt.length > 0) {
          val recovered = readHostMem(dut, 16, recoveredLen)
          assert(recovered.sameElements(pt), s"Payload mismatch for length ${pt.length}")
        }
      }
    }
  }

  test("TokenEngine: Error Rejection (Tampered HMAC, Tampered Ciphertext, Malformed Length)") {
    SimConfig.compile(TokenEngine()).doSim { dut =>
      initDut(dut)

      val signKeyHex = "0102030405060708090a0b0c0d0e0f10"
      val encKeyHex  = "1112131415161718191a1b1c1d1e1f20"
      val ivHex      = "2122232425262728292a2b2c2d2e2f30"
      val pt = "Secret Reticulum Payload".getBytes("UTF-8")

      writeHostMem(dut, 16, pt)
      dut.io.signKey #= BigInt(signKeyHex, 16)
      dut.io.encKey  #= BigInt(encKeyHex, 16)
      dut.io.iv      #= BigInt(ivHex, 16)
      startOp(dut, mode = true, pt.length)
      val sealedLen = dut.io.resultLen.toInt

      // 1. Invalid Length (< 48 bytes)
      startOp(dut, mode = false, 40)
      assert(dut.io.status.toBigInt == 3, s"Expected ERR_LEN (3), got ${dut.io.status.toBigInt}")

      // 2. Tampered Ciphertext -> ERR_HMAC (status 1)
      // Corrupt ciphertext byte at mem[20]
      val origByte = readHostMem(dut, 20, 1)(0)
      writeHostMem(dut, 20, Array((origByte ^ 0xff).toByte))

      startOp(dut, mode = false, sealedLen)
      assert(dut.io.status.toBigInt == 1, s"Expected ERR_HMAC (1), got ${dut.io.status.toBigInt}")

      // Restore ciphertext byte
      writeHostMem(dut, 20, Array(origByte))

      // 3. Tampered HMAC -> ERR_HMAC (status 1)
      // Corrupt HMAC byte at mem[sealedLen - 1]
      val hmacByte = readHostMem(dut, sealedLen - 1, 1)(0)
      writeHostMem(dut, sealedLen - 1, Array((hmacByte ^ 0x01).toByte))

      startOp(dut, mode = false, sealedLen)
      assert(dut.io.status.toBigInt == 1, s"Expected ERR_HMAC (1), got ${dut.io.status.toBigInt}")
    }
  }
}
