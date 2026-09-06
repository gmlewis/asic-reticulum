package reticulum.bus

import spinal.core._
import spinal.lib._

/**
 * Physical IO bundle for the 4-bit Quad-SPI (QSPI) slave interface.
 */
case class QspiSlaveIo() extends Bundle {
  // Physical SPI bus signals
  val sclk     = in Bool()
  val cs_n     = in Bool()
  val data_in  = in Bits(4 bits)
  val data_out = out Bits(4 bits)
  val data_oe  = out Bool()

  // Control from command decoder
  val txEnable = in Bool()

  // Internal Stream interfaces
  val rx = master Stream (Bits(8 bits))
  val tx = slave Stream (Bits(8 bits))
}

/**
 * 4-Bit Quad-SPI (QSPI) Slave Transceiver.
 *
 * Implements physical layer deserialization and serialization for 4-bit SPI:
 *  - High nibble transferred first (MSB first convention).
 *  - 2 sclk cycles per 8-bit byte.
 *  - Synchronous edge detection relative to system clock (clk >= 2 * sclk).
 *  - In RX mode (txEnable = False), deserializes 4-bit nibbles from data_in into 8-bit rx Stream.
 *  - In TX mode (txEnable = True), serializes 8-bit bytes from tx Stream into 4-bit nibbles on data_out.
 *  - data_oe controls bus turnaround (active during TX mode when cs_n is low, high-Z otherwise).
 *  - Transaction reset on cs_n deassertion (high).
 */
case class QspiSlave(fifoDepth: Int = 4) extends Component {
  val io = QspiSlaveIo()

  // Synchronize asynchronous inputs from host
  val sclkSync = BufferCC(io.sclk, False)
  val cs_nSync = BufferCC(io.cs_n, True)
  val dataSync = BufferCC(io.data_in, B(0, 4 bits))

  val sclkPrev = RegNext(sclkSync) init (False)
  val cs_nPrev = RegNext(cs_nSync) init (True)

  val sclkRise = sclkSync && !sclkPrev
  val sclkFall = !sclkSync && sclkPrev
  val cs_nRise = cs_nSync && !cs_nPrev
  val cs_nFall = !cs_nSync && cs_nPrev
  val isCsActive = !cs_nSync

  // -------------------------------------------------------------------------
  // RX Path (Host -> ASIC)
  // -------------------------------------------------------------------------
  val rxNibblePhase = RegInit(False) // False: expecting high nibble, True: expecting low nibble
  val rxHighNibble  = Reg(Bits(4 bits))

  val rxByteValid = RegInit(False)
  val rxByteData  = Reg(Bits(8 bits))

  val rxFifo = StreamFifo(Bits(8 bits), depth = fifoDepth)
  rxFifo.io.push.valid   := rxByteValid
  rxFifo.io.push.payload := rxByteData
  io.rx                  << rxFifo.io.pop

  when(rxByteValid && rxFifo.io.push.ready) {
    rxByteValid := False
  }

  when(isCsActive && !io.txEnable) {
    when(sclkRise) {
      when(!rxNibblePhase) {
        // Sample high nibble [7:4]
        rxHighNibble  := dataSync
        rxNibblePhase := True
      } otherwise {
        // Sample low nibble [3:0] and emit complete byte
        rxByteData    := Cat(rxHighNibble, dataSync)
        rxByteValid   := True
        rxNibblePhase := False
      }
    }
  }

  // -------------------------------------------------------------------------
  // TX Path (ASIC -> Host)
  // -------------------------------------------------------------------------
  val txNibblePhase = RegInit(False) // False: driving high nibble, True: driving low nibble
  val txHoldingByte = Reg(Bits(8 bits))
  val txActive      = RegInit(False)

  val txFifo = StreamFifo(Bits(8 bits), depth = fifoDepth)
  txFifo.io.push << io.tx

  // Output nibble register (driven on falling edge for host to sample on rising edge)
  val outNibble = RegInit(B(0, 4 bits))
  val outOe     = RegInit(False)

  txFifo.io.pop.ready := False

  when(isCsActive && io.txEnable) {
    outOe := True

    // When not currently transmitting a byte, grab one from txFifo
    when(!txActive) {
      when(txFifo.io.pop.valid) {
        txHoldingByte       := txFifo.io.pop.payload
        txFifo.io.pop.ready := True
        txActive            := True
        txNibblePhase       := False
        outNibble           := txFifo.io.pop.payload(7 downto 4)
      }
    } otherwise {
      when(sclkFall) {
        when(!txNibblePhase) {
          // Advance to low nibble [3:0]
          outNibble     := txHoldingByte(3 downto 0)
          txNibblePhase := True
        } otherwise {
          // Low nibble done, fetch next byte
          txNibblePhase := False
          when(txFifo.io.pop.valid) {
            txHoldingByte       := txFifo.io.pop.payload
            txFifo.io.pop.ready := True
            outNibble           := txFifo.io.pop.payload(7 downto 4)
          } otherwise {
            txActive  := False
            outNibble := B(0, 4 bits)
          }
        }
      }
    }
  } otherwise {
    outOe         := False
    txActive      := False
    txNibblePhase := False
    outNibble     := B(0, 4 bits)
  }

  // Reset phase counters when CS is deasserted
  when(!isCsActive) {
    rxNibblePhase := False
    txNibblePhase := False
    txActive      := False
    outOe         := False
  }

  io.data_out := outNibble
  io.data_oe  := outOe
}

/**
 * Generates synthesis-ready Verilog for the QspiSlave module.
 * Run with: sbt "runMain reticulum.bus.QspiSlaveVerilog"
 */
object QspiSlaveVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  config.generateVerilog(QspiSlave()).printPruned()
  println("Successfully generated Verilog in hw/gen/QspiSlave.v")
}
