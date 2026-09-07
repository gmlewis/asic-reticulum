package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class MultiTokenEngineTest extends AnyFunSuite {

  def bytesToHex(bytes: Array[Byte]): String = {
    bytes.map("%02x".format(_)).mkString
  }

  def hexToBytes(hex: String): Array[Byte] = {
    hex.sliding(2, 2).toArray.map(s => Integer.parseInt(s, 16).toByte)
  }

  def writeHostMem(dut: TokenEngine, offset: Int, data: Array[Byte]): Unit = {
    for (i <- data.indices) {
      dut.io.hostWrAddr #= offset + i
      dut.io.hostWrData #= (data(i) & 0xFF)
      dut.io.hostWrEn #= true
      dut.clockDomain.waitSampling()
    }
    dut.io.hostWrEn #= false
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

  test("MultiTokenEngine: 4-Way Parallel Seal and Open Pipeline") {
    SimConfig.compile(TokenEngine(numEngines = 4)).doSim { dut =>
      initDut(dut)

      val numJobs = 4
      val keys = (0 until numJobs).map { i =>
        val signKey = hexToBytes(f"0102030405060708090a0b0c0d0e${i}%02x10")
        val encKey  = hexToBytes(f"1112131415161718191a1b1c1d1e${i}%02x20")
        val iv      = hexToBytes(f"2122232425262728292a2b2c2d2e${i}%02x30")
        val pt      = f"Reticulum Token Cluster Job #$i%03d".getBytes("UTF-8")
        assert(pt.length == 32)
        (signKey, encKey, iv, pt)
      }

      // 1. Dispatch 4 Seal jobs sequentially without waiting for previous jobs to complete
      for (i <- 0 until numJobs) {
        val (signKey, encKey, iv, pt) = keys(i)

        // Write plaintext into mem[16..47]
        writeHostMem(dut, 16, pt)

        dut.io.signKey #= BigInt(bytesToHex(signKey), 16)
        dut.io.encKey  #= BigInt(bytesToHex(encKey), 16)
        dut.io.iv      #= BigInt(bytesToHex(iv), 16)
        dut.io.dataLen #= pt.length
        dut.io.mode    #= true
        dut.io.start   #= true
        dut.clockDomain.waitSampling()
        dut.io.start   #= false
        dut.clockDomain.waitSampling(2)
      }

      // Verify all 4 engines are now occupied
      assert(dut.io.busy.toBoolean, "All 4 engines should be occupied, asserting busy")

      // 2. Read back 4 sealed tokens in FIFO order as they complete
      val sealedTokens = collection.mutable.ArrayBuffer[Array[Byte]]()
      for (i <- 0 until numJobs) {
        // Wait for next job in FIFO to become done
        var waitCycles = 0
        while (!dut.io.done.toBoolean && waitCycles < 4000) {
          dut.clockDomain.waitSampling(10)
          waitCycles += 10
        }
        assert(dut.io.done.toBoolean, s"Job $i failed to complete within timeout")
        assert(dut.io.status.toBigInt == 0, s"Job $i Seal status should be OK (0)")

        val sealedLen = dut.io.resultLen.toInt
        assert(sealedLen == 96, s"Expected sealed len 96 for Job $i, got $sealedLen")
        val token = readHostMem(dut, 0, sealedLen)
        sealedTokens += token

        // Verify IV matches Job i's IV
        val (_, _, expIv, _) = keys(i)
        assert(token.slice(0, 16).sameElements(expIv), s"Job $i IV mismatch")

        // Clear IRQ to retire Job i and advance to Job i+1
        dut.io.irqClear #= true
        dut.clockDomain.waitSampling()
        dut.io.irqClear #= false
        dut.clockDomain.waitSampling(2)
      }

      // After retiring all 4 jobs, queue should be empty
      assert(!dut.io.done.toBoolean, "done should be false after all jobs retired")
      assert(!dut.io.busy.toBoolean, "busy should be false after all jobs retired")

      // 3. Dispatch 4 Open jobs back-to-back using the sealed tokens
      for (i <- 0 until numJobs) {
        val (signKey, encKey, _, _) = keys(i)
        val token = sealedTokens(i)

        // Write token into mem[0..95]
        writeHostMem(dut, 0, token)

        dut.io.signKey #= BigInt(bytesToHex(signKey), 16)
        dut.io.encKey  #= BigInt(bytesToHex(encKey), 16)
        dut.io.dataLen #= token.length
        dut.io.mode    #= false
        dut.io.start   #= true
        dut.clockDomain.waitSampling()
        dut.io.start   #= false
        dut.clockDomain.waitSampling(2)
      }

      // 4. Read back 4 decrypted plaintexts and verify exact recovery
      for (i <- 0 until numJobs) {
        var waitCycles = 0
        while (!dut.io.done.toBoolean && waitCycles < 4000) {
          dut.clockDomain.waitSampling(10)
          waitCycles += 10
        }
        assert(dut.io.done.toBoolean, s"Open Job $i failed to complete within timeout")
        assert(dut.io.status.toBigInt == 0, s"Open Job $i status should be OK (0)")

        val openedLen = dut.io.resultLen.toInt
        assert(openedLen == 32, s"Expected opened len 32 for Job $i, got $openedLen")

        val recoveredPt = readHostMem(dut, 16, openedLen)
        val (_, _, _, expPt) = keys(i)
        assert(recoveredPt.sameElements(expPt), s"Job $i plaintext recovery mismatch")

        // Clear IRQ
        dut.io.irqClear #= true
        dut.clockDomain.waitSampling()
        dut.io.irqClear #= false
        dut.clockDomain.waitSampling(2)
      }
    }
  }

  test("MultiTokenEngine: Backpressure and Dynamic Engine Reallocation") {
    SimConfig.compile(TokenEngine(numEngines = 4)).doSim { dut =>
      initDut(dut)

      val signKeyHex = "0102030405060708090a0b0c0d0e0f10"
      val encKeyHex  = "1112131415161718191a1b1c1d1e1f20"
      val ivHex      = "2122232425262728292a2b2c2d2e2f30"
      val pt         = "Backpressure Test Payload 123456".getBytes("UTF-8")

      // Submit 4 jobs to fill all engines
      for (_ <- 0 until 4) {
        writeHostMem(dut, 16, pt)
        dut.io.signKey #= BigInt(signKeyHex, 16)
        dut.io.encKey  #= BigInt(encKeyHex, 16)
        dut.io.iv      #= BigInt(ivHex, 16)
        dut.io.dataLen #= pt.length
        dut.io.mode    #= true
        dut.io.start   #= true
        dut.clockDomain.waitSampling()
        dut.io.start   #= false
        dut.clockDomain.waitSampling(2)
      }

      assert(dut.io.busy.toBoolean, "Pool should be busy when all 4 engines are full")

      // Wait for Job 0 to finish
      dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)

      // Retire Job 0
      dut.io.irqClear #= true
      dut.clockDomain.waitSampling()
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(2)

      // Busy should deassert because Engine 0 is now free
      assert(!dut.io.busy.toBoolean, "Pool should no longer be busy after retiring one job")

      // Submit Job 5 into the newly freed engine
      val pt5 = "Fifth Job Into Freed Engine 000!".getBytes("UTF-8")
      writeHostMem(dut, 16, pt5)
      dut.io.signKey #= BigInt(signKeyHex, 16)
      dut.io.encKey  #= BigInt(encKeyHex, 16)
      dut.io.iv      #= BigInt(ivHex, 16)
      dut.io.dataLen #= pt5.length
      dut.io.mode    #= true
      dut.io.start   #= true
      dut.clockDomain.waitSampling()
      dut.io.start   #= false
      dut.clockDomain.waitSampling(2)

      // Now all 4 engines are busy again
      assert(dut.io.busy.toBoolean, "Pool should be busy again after 5th job submitted")
    }
  }

  test("MultiTokenEngine: Abort Resets All Engines and Queue") {
    SimConfig.compile(TokenEngine(numEngines = 4)).doSim { dut =>
      initDut(dut)

      val signKeyHex = "0102030405060708090a0b0c0d0e0f10"
      val encKeyHex  = "1112131415161718191a1b1c1d1e1f20"
      val ivHex      = "2122232425262728292a2b2c2d2e2f30"
      val pt         = "Abort Test Payload".getBytes("UTF-8")

      // Submit 2 jobs
      for (_ <- 0 until 2) {
        writeHostMem(dut, 16, pt)
        dut.io.signKey #= BigInt(signKeyHex, 16)
        dut.io.encKey  #= BigInt(encKeyHex, 16)
        dut.io.iv      #= BigInt(ivHex, 16)
        dut.io.dataLen #= pt.length
        dut.io.mode    #= true
        dut.io.start   #= true
        dut.clockDomain.waitSampling()
        dut.io.start   #= false
        dut.clockDomain.waitSampling(5)
      }

      // Trigger Abort
      dut.io.abort #= true
      dut.clockDomain.waitSampling(2)
      dut.io.abort #= false
      dut.clockDomain.waitSampling(5)

      // Verify busy is false and no jobs remain
      assert(!dut.io.busy.toBoolean, "busy should be false after abort")
    }
  }
}
