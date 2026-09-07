package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class HmacSha256Test extends AnyFunSuite {

  def keyToBigInt(keyBytes: Array[Byte]): BigInt = {
    var bi = BigInt(0)
    for (i <- keyBytes.indices) {
      val b = BigInt(keyBytes(i) & 0xFF)
      val shift = 512 - (i + 1) * 8
      bi |= (b << shift)
    }
    bi
  }

  def hexToBytes(hex: String): Array[Byte] = {
    hex.sliding(2, 2).toArray.map(s => Integer.parseInt(s, 16).toByte)
  }

  def bigIntToHexBE(bi: BigInt, numBytes: Int = 32): String = {
    val s = bi.toString(16)
    val pad = "0" * math.max(0, numBytes * 2 - s.length)
    pad + s
  }

  def runHmacTest(dut: HmacSha256, key: Array[Byte], msg: Array[Byte], expHex: String): Unit = {
    dut.io.key #= keyToBigInt(key)
    dut.io.keyLen #= key.length
    dut.io.msgValid #= false
    dut.io.msgLast #= false
    dut.io.msgByte #= 0
    dut.io.start #= true
    dut.clockDomain.waitSampling()
    dut.io.start #= false
    dut.clockDomain.waitSampling()

    // Wait until DUT is ready to receive message bytes
    dut.clockDomain.waitSamplingWhere(dut.io.msgReady.toBoolean)
    

    // Stream message bytes
    if (msg.isEmpty) {
      // Empty message: pulse msgValid and msgLast with 0 length if needed,
      // or standard empty message
    } else {
      var i = 0
      while (i < msg.length) {
        dut.clockDomain.waitSamplingWhere(dut.io.msgReady.toBoolean)
        dut.io.msgByte #= (msg(i) & 0xFF)
        dut.io.msgValid #= true
        dut.io.msgLast #= (i == msg.length - 1)
        dut.clockDomain.waitSampling()
        dut.io.msgValid #= false
        dut.io.msgLast #= false
        i += 1
      }
      dut.io.msgValid #= false
      dut.io.msgLast #= false
    }

    // Wait for completion
    dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)
    println(s"DUT innerDigest:   ${bigIntToHexBE(dut.innerDigest.toBigInt, 32)}")
    println(s"DUT runningState:  ${bigIntToHexBE(dut.runningState.toBigInt, 32)}")
    val actual = bigIntToHexBE(dut.io.hmac.toBigInt, 32)
    println(s"DUT produced: $actual (expected: $expHex)")
    assert(actual == expHex, s"HMAC mismatch: expected $expHex, got $actual")
    dut.clockDomain.waitSampling()
  }

  test("HmacSha256: RFC 4231 Test Cases 1, 2, 3") {
    SimConfig.compile(HmacSha256()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.msgValid #= false
      dut.io.msgLast #= false
      dut.io.msgByte #= 0
      dut.io.key #= 0
      dut.io.keyLen #= 0
      dut.clockDomain.waitSampling(5)

      // Case 1: 20-byte key, "Hi There" (8 bytes)
      val k1 = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
      val m1 = "Hi There".getBytes("UTF-8")
      val exp1 = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
      runHmacTest(dut, k1, m1, exp1)

      // Case 2: 4-byte key "Jefe", 28-byte message
      val k2 = "Jefe".getBytes("UTF-8")
      val m2 = "what do ya want for nothing?".getBytes("UTF-8")
      val exp2 = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
      runHmacTest(dut, k2, m2, exp2)

      // Case 3: 20-byte key of 0xaa, 50-byte message of 0xdd
      val k3 = Array.fill[Byte](20)(0xaa.toByte)
      val m3 = Array.fill[Byte](50)(0xdd.toByte)
      val exp3 = "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"
      runHmacTest(dut, k3, m3, exp3)
    }
  }

  test("HmacSha256: Reticulum Multi-Block (160 Bytes) HMAC") {
    SimConfig.compile(HmacSha256()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.msgValid #= false
      dut.io.msgLast #= false
      dut.io.msgByte #= 0
      dut.io.key #= 0
      dut.io.keyLen #= 0
      dut.clockDomain.waitSampling(5)

      // 16-byte Reticulum signing key
      val key = hexToBytes("000102030405060708090a0b0c0d0e0f")
      val msg = (0 until 160).map(i => (i & 0xFF).toByte).toArray

      // Let python compute reference
      // key: 000102030405060708090a0b0c0d0e0f
      // msg: bytes(range(160))
      // Python verification:
      // import hmac, hashlib
      // hmac.new(bytes.fromhex("000102030405060708090a0b0c0d0e0f"), bytes(range(160)), hashlib.sha256).hexdigest()
      val expHex = "634b8458bf79e131da144436c9474b6320b5b1c886861037a22d49d018b431d3"
      runHmacTest(dut, key, msg, expHex)
    }
  }

  test("HmacSha256: Exactly 64 Bytes Message Test") {
    SimConfig.compile(HmacSha256()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.msgValid #= false
      dut.io.msgLast #= false
      dut.io.msgByte #= 0
      dut.io.key #= 0
      dut.io.keyLen #= 0
      dut.clockDomain.waitSampling(5)

      val key = hexToBytes("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
      val msg = hexToBytes("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf0077b41ab35e03e7b85319535216f6d31bdfbba5c263a541ee7008fe89b014c41d4f148a2330fa2d0debf2bd7f773619")
      assert(msg.length == 64)
      val expHex = "2e028164b31ebbea604a102d1f80280eccd830871b43a843e5952776bc044116"

      runHmacTest(dut, key, msg, expHex)
    }
  }
}

