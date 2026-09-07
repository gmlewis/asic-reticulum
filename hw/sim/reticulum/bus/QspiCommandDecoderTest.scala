package reticulum.bus

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class QspiCommandDecoderTest extends AnyFunSuite {

  def sendCommand(dut: QspiCommandDecoder, opcode: Int, payload: Seq[Int]): Unit = {
    val lenMsb = (payload.length >> 8) & 0xFF
    val lenLsb = payload.length & 0xFF

    val fullPacket = Seq(opcode, lenMsb, lenLsb) ++ payload
    for (b <- fullPacket) {
      dut.io.rx.valid #= true
      dut.io.rx.payload #= b
      dut.clockDomain.waitSampling()
      while (!dut.io.rx.ready.toBoolean) {
        dut.clockDomain.waitSampling()
      }
    }
    dut.io.rx.valid #= false
  }

  def initInputs(dut: QspiCommandDecoder): Unit = {
    dut.io.cs_n #= false
    dut.io.rx.valid #= false
    dut.io.rx.payload #= 0
    dut.io.tx.ready #= true

    dut.io.stampBusy #= false
    dut.io.stampDone #= false
    dut.io.stampMeetsTarget #= false
    dut.io.stampIrq #= false
    dut.io.stampWinningZeros #= 0
    dut.io.stampWinningNonce #= 0L
    dut.io.stampRoundsEvaluated #= 0L
    dut.io.stampWinningDigest #= 0
    dut.io.stampWinningCandidate #= 0

    dut.io.x25519Busy #= false
    dut.io.x25519Done #= false
    dut.io.x25519Irq #= false
    dut.io.x25519Result #= 0

    dut.io.tokenBusy #= false
    dut.io.tokenDone #= false
    dut.io.tokenIrq #= false
    dut.io.tokenStatus #= 0
    dut.io.tokenResultLen #= 0
    dut.io.tokenResultOffset #= 0
    dut.io.tokenHostRdData #= 0
  }

  test("QspiCommandDecoder: OP_STATUS read") {
    SimConfig.compile(QspiCommandDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)

      dut.io.stampBusy #= false
      dut.io.stampDone #= true
      dut.io.stampMeetsTarget #= true
      dut.io.stampIrq #= true
      dut.io.stampRoundsEvaluated #= 0x1234L
      dut.clockDomain.waitSampling(5)

      // Send OP_STATUS with length 0
      sendCommand(dut, QspiOpcode.OP_STATUS, Seq())

      // Collect 4 status bytes
      val received = collection.mutable.ArrayBuffer[Int]()
      for (_ <- 0 until 4) {
        while (!dut.io.tx.valid.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        received += dut.io.tx.payload.toInt
        dut.clockDomain.waitSampling()
      }

      // Expected:
      // Byte 0: status flag (x25519Irq=0, x25519Busy=0, x25519Done=0, stampIrq=1, stampBusy=0, stampMeetsTarget=1, stampDone=1)
      //         -> 0b0000_1011 = 0x0B
      // Byte 1: 0x10 (version 1.0)
      // Byte 2: rounds high byte 0x12
      // Byte 3: rounds low byte 0x34
      assert(received == Seq(0x0B, 0x10, 0x12, 0x34), s"Status mismatch: got $received")
    }
  }

  test("QspiCommandDecoder: OP_ABORT and OP_IRQ_CLEAR pulses") {
    SimConfig.compile(QspiCommandDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.io.tx.ready #= false
      dut.io.stampBusy #= true
      dut.io.stampIrq #= true
      dut.io.x25519Busy #= true
      dut.io.x25519Irq #= true
      dut.clockDomain.waitSampling(5)

      // Send OP_ABORT
      var abortSeen = false
      var x25519AbortSeen = false
      fork {
        for (_ <- 0 until 20) {
          if (dut.io.stampAbort.toBoolean) abortSeen = true
          if (dut.io.x25519Abort.toBoolean) x25519AbortSeen = true
          dut.clockDomain.waitSampling()
        }
      }
      sendCommand(dut, QspiOpcode.OP_ABORT, Seq())
      dut.clockDomain.waitSampling(5)
      assert(abortSeen, "stampAbort was not pulsed on OP_ABORT")
      assert(x25519AbortSeen, "x25519Abort was not pulsed on OP_ABORT")

      // Send OP_IRQ_CLEAR
      var irqClearSeen = false
      var x25519IrqClearSeen = false
      fork {
        for (_ <- 0 until 20) {
          if (dut.io.stampIrqClear.toBoolean) irqClearSeen = true
          if (dut.io.x25519IrqClear.toBoolean) x25519IrqClearSeen = true
          dut.clockDomain.waitSampling()
        }
      }
      sendCommand(dut, QspiOpcode.OP_IRQ_CLEAR, Seq())
      dut.clockDomain.waitSampling(5)
      assert(irqClearSeen, "stampIrqClear was not pulsed on OP_IRQ_CLEAR")
      assert(x25519IrqClearSeen, "x25519IrqClear was not pulsed on OP_IRQ_CLEAR")
    }
  }

  test("QspiCommandDecoder: OP_STAMP_GRIND parameter reception & start pulse") {
    SimConfig.compile(QspiCommandDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(5)

      val targetCost = 5
      val midstateBytes = (0 until 32).map(i => i + 1)
      val baseCandBytes = (0 until 32).map(i => 0x55)
      val totalLenBytes = Seq(0, 0, 0, 0, 0, 0, 2, 0) // 0x200 = 512 bits
      val startNonceBytes = Seq(0, 0, 0, 0, 0, 0, 0, 10)
      val maxRoundsBytes = Seq(0, 0, 0, 0, 0, 0, 0, 100)

      val payload = Seq(targetCost) ++ midstateBytes ++ baseCandBytes ++ totalLenBytes ++ startNonceBytes ++ maxRoundsBytes
      assert(payload.length == 89)

      var startSeen = false
      fork {
        for (_ <- 0 until 200) {
          if (dut.io.stampStart.toBoolean) startSeen = true
          dut.clockDomain.waitSampling()
        }
      }

      sendCommand(dut, QspiOpcode.OP_STAMP_GRIND, payload)
      dut.clockDomain.waitSampling(5)

      assert(startSeen, "stampStart was not pulsed on OP_STAMP_GRIND")
      assert(dut.io.stampTargetCost.toInt == 5)
      assert(dut.io.stampTotalLengthBits.toBigInt == 0x200L)
      assert(dut.io.stampStartNonce.toBigInt == 10L)
      assert(dut.io.stampMaxRounds.toBigInt == 100L)
    }
  }

  test("QspiCommandDecoder: OP_STAMP_READ response streaming (82 bytes)") {
    SimConfig.compile(QspiCommandDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)

      dut.io.stampBusy #= false
      dut.io.stampDone #= true
      dut.io.stampMeetsTarget #= true
      dut.io.stampIrq #= true
      dut.io.stampWinningZeros #= 7
      dut.io.stampWinningNonce #= 0x1122334455667788L
      dut.io.stampRoundsEvaluated #= 0x0102030405060708L
      dut.io.stampWinningDigest #= BigInt("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 16)
      dut.io.stampWinningCandidate #= BigInt("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", 16)
      dut.clockDomain.waitSampling(5)

      // Send OP_STAMP_READ with length 0
      sendCommand(dut, QspiOpcode.OP_STAMP_READ, Seq())

      // Collect 82 result bytes
      val received = collection.mutable.ArrayBuffer[Int]()
      for (_ <- 0 until 82) {
        while (!dut.io.tx.valid.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        received += dut.io.tx.payload.toInt
        dut.clockDomain.waitSampling()
      }

      assert(received.length == 82)
      // Byte 0: status flag (0x0B)
      assert(received(0) == 0x0B)
      // Byte 1: winning zeros (7)
      assert(received(1) == 7)
      // Bytes 2..9: winning nonce 0x1122334455667788
      val gotNonce = received.slice(2, 10).foldLeft(0L)((acc, b) => (acc << 8) | b)
      assert(gotNonce == 0x1122334455667788L, f"Nonce mismatch: got 0x$gotNonce%x")
      // Bytes 10..17: rounds evaluated 0x0102030405060708
      val gotRounds = received.slice(10, 18).foldLeft(0L)((acc, b) => (acc << 8) | b)
      assert(gotRounds == 0x0102030405060708L, f"Rounds mismatch: got 0x$gotRounds%x")
    }
  }

  test("QspiCommandDecoder: OP_X25519_MULT parameter reception & start pulse (64 bytes)") {
    SimConfig.compile(QspiCommandDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(5)

      val scalarBytes = (0 until 32).map(i => i + 0x10)
      val uCoordBytes = (0 until 32).map(i => i + 0x50)
      val payload = scalarBytes ++ uCoordBytes
      assert(payload.length == 64)

      var startSeen = false
      fork {
        for (_ <- 0 until 150) {
          if (dut.io.x25519Start.toBoolean) startSeen = true
          dut.clockDomain.waitSampling()
        }
      }

      sendCommand(dut, QspiOpcode.OP_X25519_MULT, payload)
      dut.clockDomain.waitSampling(5)

      assert(startSeen, "x25519Start was not pulsed on OP_X25519_MULT")
    }
  }

  test("QspiCommandDecoder: OP_X25519_READ response streaming (32 bytes)") {
    SimConfig.compile(QspiCommandDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)

      val expResultBytes = (0 until 32).map(i => (i * 7) & 0xFF)
      var expResultBigInt = BigInt(0)
      for (i <- 0 until 32) {
        expResultBigInt |= (BigInt(expResultBytes(i)) << (i * 8))
      }
      dut.io.x25519Result #= expResultBigInt
      dut.clockDomain.waitSampling(5)

      // Send OP_X25519_READ with length 0
      sendCommand(dut, QspiOpcode.OP_X25519_READ, Seq())

      // Collect 32 result bytes
      val received = collection.mutable.ArrayBuffer[Int]()
      for (_ <- 0 until 32) {
        while (!dut.io.tx.valid.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        received += dut.io.tx.payload.toInt
        dut.clockDomain.waitSampling()
      }

      assert(received.length == 32)
      assert(received.toSeq == expResultBytes, s"X25519 result mismatch: got $received, expected $expResultBytes")
    }
  }
}
