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

  test("QspiCommandDecoder: OP_STATUS read") {
    SimConfig.compile(QspiCommandDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cs_n #= false
      dut.io.rx.valid #= false
      dut.io.rx.payload #= 0
      dut.io.tx.ready #= true

      dut.io.stampBusy #= false
      dut.io.stampDone #= true
      dut.io.stampMeetsTarget #= true
      dut.io.stampIrq #= true
      dut.io.stampWinningZeros #= 3
      dut.io.stampWinningNonce #= 42L
      dut.io.stampRoundsEvaluated #= 0x1234L
      dut.io.stampWinningDigest #= 0
      dut.io.stampWinningCandidate #= 0
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
      // Byte 0: status flag (irq=1, busy=0, meetsTarget=1, done=1) -> 0b0000_1011 = 0x0B
      // Byte 1: 0x10 (version 1.0)
      // Byte 2: rounds high byte 0x12
      // Byte 3: rounds low byte 0x34
      assert(received == Seq(0x0B, 0x10, 0x12, 0x34), s"Status mismatch: got $received")
    }
  }

  test("QspiCommandDecoder: OP_ABORT and OP_IRQ_CLEAR pulses") {
    SimConfig.compile(QspiCommandDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cs_n #= false
      dut.io.rx.valid #= false
      dut.io.rx.payload #= 0
      dut.io.tx.ready #= false
      dut.io.stampBusy #= true
      dut.io.stampDone #= false
      dut.io.stampMeetsTarget #= false
      dut.io.stampIrq #= true
      dut.io.stampWinningZeros #= 0
      dut.io.stampWinningNonce #= 0L
      dut.io.stampRoundsEvaluated #= 0L
      dut.io.stampWinningDigest #= 0
      dut.io.stampWinningCandidate #= 0
      dut.clockDomain.waitSampling(5)

      // Send OP_ABORT
      var abortSeen = false
      fork {
        for (_ <- 0 until 20) {
          if (dut.io.stampAbort.toBoolean) abortSeen = true
          dut.clockDomain.waitSampling()
        }
      }
      sendCommand(dut, QspiOpcode.OP_ABORT, Seq())
      dut.clockDomain.waitSampling(5)
      assert(abortSeen, "stampAbort was not pulsed on OP_ABORT")

      // Send OP_IRQ_CLEAR
      var irqClearSeen = false
      fork {
        for (_ <- 0 until 20) {
          if (dut.io.stampIrqClear.toBoolean) irqClearSeen = true
          dut.clockDomain.waitSampling()
        }
      }
      sendCommand(dut, QspiOpcode.OP_IRQ_CLEAR, Seq())
      dut.clockDomain.waitSampling(5)
      assert(irqClearSeen, "stampIrqClear was not pulsed on OP_IRQ_CLEAR")
    }
  }

  test("QspiCommandDecoder: OP_STAMP_GRIND parameter reception & start pulse") {
    SimConfig.compile(QspiCommandDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cs_n #= false
      dut.io.rx.valid #= false
      dut.io.rx.payload #= 0
      dut.io.tx.ready #= false
      dut.io.stampBusy #= false
      dut.io.stampDone #= false
      dut.io.stampMeetsTarget #= false
      dut.io.stampIrq #= false
      dut.io.stampWinningZeros #= 0
      dut.io.stampWinningNonce #= 0L
      dut.io.stampRoundsEvaluated #= 0L
      dut.io.stampWinningDigest #= 0
      dut.io.stampWinningCandidate #= 0
      dut.clockDomain.waitSampling(5)

      // Form 89-byte payload:
      // targetCost = 5 (1B)
      // midstate = 32 bytes (0x11, 0x12, ...)
      // baseCandidate = 32 bytes (0x21, 0x22, ...)
      // totalLengthBits = 0x0000000000000200 (8B)
      // startNonce = 0x000000000000000A (8B)
      // maxRounds = 0x0000000000000064 (8B, 100 rounds)
      val targetCost = 5
      val midstateBytes = (0 until 32).map(i => (0x10 + i) & 0xFF)
      val baseCandBytes = (0 until 32).map(i => (0x40 + i) & 0xFF)
      val totalLenBytes = Seq(0, 0, 0, 0, 0, 0, 2, 0)
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
      dut.io.cs_n #= false
      dut.io.rx.valid #= false
      dut.io.rx.payload #= 0
      dut.io.tx.ready #= true

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
}
