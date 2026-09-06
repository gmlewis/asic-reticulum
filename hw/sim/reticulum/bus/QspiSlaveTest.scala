package reticulum.bus

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class QspiSlaveTest extends AnyFunSuite {

  /**
   * Helper: Simulates host sending one byte over 4-bit QSPI (Mode 0).
   * Mode 0: Data driven before/at rising edge, sampled on rising edge.
   * Period of sclk = 4 system clock cycles (oversampling factor of 4).
   */
  def qspiSendByte(dut: QspiSlave, byteVal: Int): Unit = {
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
   * Host samples data_out on rising edges of sclk.
   */
  def qspiReceiveByte(dut: QspiSlave): Int = {
    // Cycle 1: Sample high nibble on rising edge
    dut.clockDomain.waitSampling(4)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    val highNibble = dut.io.data_out.toInt
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false

    // Cycle 2: Sample low nibble on rising edge
    dut.clockDomain.waitSampling(4)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    val lowNibble = dut.io.data_out.toInt
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false

    (highNibble << 4) | lowNibble
  }

  test("QspiSlave: multi-byte RX reception (Host -> ASIC)") {
    SimConfig.compile(QspiSlave()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.sclk #= false
      dut.io.cs_n #= true
      dut.io.data_in #= 0
      dut.io.txEnable #= false
      dut.io.rx.ready #= true
      dut.io.tx.valid #= false
      dut.io.tx.payload #= 0
      dut.clockDomain.waitSampling(5)

      val testBytes = Seq(0xA5, 0x5A, 0x12, 0x34, 0xFF, 0x00, 0xC3)

      // Assert CS active low
      dut.io.cs_n #= false
      dut.clockDomain.waitSampling(4)

      val received = collection.mutable.ArrayBuffer[Int]()

      // Fork a monitor thread to collect received bytes from dut.io.rx
      fork {
        while (received.length < testBytes.length) {
          if (dut.io.rx.valid.toBoolean && dut.io.rx.ready.toBoolean) {
            received += dut.io.rx.payload.toInt
          }
          dut.clockDomain.waitSampling()
        }
      }

      for (b <- testBytes) {
        qspiSendByte(dut, b)
      }

      // Deassert CS
      dut.clockDomain.waitSampling(4)
      dut.io.cs_n #= true
      dut.clockDomain.waitSampling(10)

      assert(received == testBytes, s"RX mismatch: got $received, expected $testBytes")
      assert(!dut.io.data_oe.toBoolean, "data_oe should be false in RX mode")
    }
  }

  test("QspiSlave: multi-byte TX transmission (ASIC -> Host)") {
    SimConfig.compile(QspiSlave()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.sclk #= false
      dut.io.cs_n #= true
      dut.io.data_in #= 0
      dut.io.txEnable #= false
      dut.io.rx.ready #= false
      dut.io.tx.valid #= false
      dut.io.tx.payload #= 0
      dut.clockDomain.waitSampling(5)

      val txBytes = Seq(0xDE, 0xAD, 0xBE, 0xEF, 0x42)

      // Preload TX FIFO
      fork {
        for (b <- txBytes) {
          dut.io.tx.valid #= true
          dut.io.tx.payload #= b
          dut.clockDomain.waitSampling()
          while (!dut.io.tx.ready.toBoolean) {
            dut.clockDomain.waitSampling()
          }
        }
        dut.io.tx.valid #= false
      }

      dut.clockDomain.waitSampling(5)

      // Host initiates read: assert CS low and set txEnable
      dut.io.cs_n #= false
      dut.io.txEnable #= true
      dut.clockDomain.waitSampling(4)

      assert(dut.io.data_oe.toBoolean, "data_oe should be true during TX")

      val received = collection.mutable.ArrayBuffer[Int]()
      for (_ <- txBytes.indices) {
        received += qspiReceiveByte(dut)
      }

      dut.io.cs_n #= true
      dut.io.txEnable #= false
      dut.clockDomain.waitSampling(5)

      assert(received == txBytes, s"TX mismatch: got $received, expected $txBytes")
      assert(!dut.io.data_oe.toBoolean, "data_oe should deassert when cs_n is high")
    }
  }

  test("QspiSlave: CS deassertion resets partial nibble frame") {
    SimConfig.compile(QspiSlave()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.sclk #= false
      dut.io.cs_n #= true
      dut.io.data_in #= 0
      dut.io.txEnable #= false
      dut.io.rx.ready #= true
      dut.io.tx.valid #= false
      dut.io.tx.payload #= 0
      dut.clockDomain.waitSampling(5)

      // Transaction 1: Send only HIGH nibble of an incomplete byte (0x9?), then abort CS
      dut.io.cs_n #= false
      dut.clockDomain.waitSampling(2)
      dut.io.data_in #= 0x9
      dut.clockDomain.waitSampling(2)
      dut.io.sclk #= true
      dut.clockDomain.waitSampling(2)
      dut.io.sclk #= false
      dut.clockDomain.waitSampling(2)

      // Abort CS before low nibble
      dut.io.cs_n #= true
      dut.clockDomain.waitSampling(10)
      assert(!dut.io.rx.valid.toBoolean, "Partial nibble should not trigger rx.valid")

      // Transaction 2: Fresh complete byte (0x5A)
      val received = collection.mutable.ArrayBuffer[Int]()
      fork {
        while (received.isEmpty) {
          if (dut.io.rx.valid.toBoolean && dut.io.rx.ready.toBoolean) {
            received += dut.io.rx.payload.toInt
          }
          dut.clockDomain.waitSampling()
        }
      }

      dut.io.cs_n #= false
      dut.clockDomain.waitSampling(2)
      qspiSendByte(dut, 0x5A)
      dut.clockDomain.waitSampling(4)
      dut.io.cs_n #= true
      dut.clockDomain.waitSampling(5)

      assert(received == Seq(0x5A), s"Expected 0x5A after frame reset, got $received")
    }
  }
}
