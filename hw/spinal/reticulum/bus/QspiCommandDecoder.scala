package reticulum.bus

import spinal.core._
import spinal.lib._

object QspiOpcode {
  val OP_STATUS      = 0x01
  val OP_ABORT       = 0x02
  val OP_IRQ_CLEAR   = 0x03
  val OP_STAMP_GRIND = 0x10
  val OP_STAMP_READ  = 0x11
}

/**
 * IO bundle for QspiCommandDecoder.
 */
case class QspiCommandDecoderIo() extends Bundle {
  val cs_n = in Bool()

  // Stream interface to/from QspiSlave
  val rx       = slave Stream (Bits(8 bits))
  val tx       = master Stream (Bits(8 bits))
  val txEnable = out Bool()

  // Control & Status lines to/from Stamper
  val stampStart           = out Bool()
  val stampAbort           = out Bool()
  val stampIrqClear        = out Bool()
  val stampTargetCost      = out UInt(8 bits)
  val stampMidstate        = out Bits(256 bits)
  val stampBaseCandidate   = out Bits(256 bits)
  val stampTotalLengthBits = out UInt(64 bits)
  val stampStartNonce      = out UInt(64 bits)
  val stampMaxRounds       = out UInt(64 bits)

  val stampBusy             = in Bool()
  val stampDone             = in Bool()
  val stampMeetsTarget      = in Bool()
  val stampIrq              = in Bool()
  val stampWinningCandidate = in Bits(256 bits)
  val stampWinningDigest    = in Bits(256 bits)
  val stampWinningZeros     = in UInt(8 bits)
  val stampWinningNonce     = in UInt(64 bits)
  val stampRoundsEvaluated  = in UInt(64 bits)
}

object DecoderState extends SpinalEnum {
  val IDLE, LEN_MSB, LEN_LSB, RX_STAMP_PAYLOAD, RX_DISCARD, TX_STATUS, TX_STAMP_RESULT = newElement()
}

/**
 * Command Decoder FSM for Reticulum QSPI Host Interconnect.
 *
 * Framing: [OpCode: 1B] [Length: 2B big-endian] [Payload: Length bytes]
 */
case class QspiCommandDecoder() extends Component {
  val io = QspiCommandDecoderIo()

  val state = RegInit(DecoderState.IDLE)

  val regOpcode = Reg(UInt(8 bits))
  val regLenMsb = Reg(Bits(8 bits))
  val regLen    = Reg(UInt(16 bits))

  val bytesRemaining = Reg(UInt(16 bits))
  val byteIndex      = Reg(UInt(16 bits))

  // Stamper latched configuration registers
  val cfgTargetCost      = Reg(UInt(8 bits)) init (0)
  val cfgMidstate        = Reg(Bits(256 bits)) init (0)
  val cfgBaseCandidate   = Reg(Bits(256 bits)) init (0)
  val cfgTotalLengthBits = Reg(UInt(64 bits)) init (0)
  val cfgStartNonce      = Reg(UInt(64 bits)) init (0)
  val cfgMaxRounds       = Reg(UInt(64 bits)) init (0)

  val regStampStart    = RegInit(False)
  val regStampAbort    = RegInit(False)
  val regStampIrqClear = RegInit(False)

  // Status buffer for reading
  val statusBytes = Vec(Bits(8 bits), 4)
  val statusFlagByte = Cat(
    B(0, 4 bits),
    io.stampIrq,
    io.stampBusy,
    io.stampMeetsTarget,
    io.stampDone
  )
  statusBytes(0) := statusFlagByte
  statusBytes(1) := B"8'h10" // Reticulum ASIC Architecture v1.0
  statusBytes(2) := io.stampRoundsEvaluated(15 downto 8).asBits
  statusBytes(3) := io.stampRoundsEvaluated(7 downto 0).asBits

  // Snapshot of winning results for transmission
  val snapStatus       = Reg(Bits(8 bits))
  val snapZeros        = Reg(UInt(8 bits))
  val snapNonce        = Reg(UInt(64 bits))
  val snapRounds       = Reg(UInt(64 bits))
  val snapDigest       = Reg(Bits(256 bits))
  val snapCandidate    = Reg(Bits(256 bits))

  def getResultByte(idx: UInt): Bits = {
    val b = Bits(8 bits)
    switch(idx) {
      is(0) { b := snapStatus }
      is(1) { b := snapZeros.asBits }
      for (i <- 0 until 8) {
        is(2 + i) {
          val msb = 63 - i * 8
          val lsb = msb - 7
          b := snapNonce(msb downto lsb).asBits
        }
      }
      for (i <- 0 until 8) {
        is(10 + i) {
          val msb = 63 - i * 8
          val lsb = msb - 7
          b := snapRounds(msb downto lsb).asBits
        }
      }
      for (i <- 0 until 32) {
        is(18 + i) {
          val msb = 255 - i * 8
          val lsb = msb - 7
          b := snapDigest(msb downto lsb)
        }
      }
      for (i <- 0 until 32) {
        is(50 + i) {
          val msb = 255 - i * 8
          val lsb = msb - 7
          b := snapCandidate(msb downto lsb)
        }
      }
      default { b := B(0, 8 bits) }
    }
    b
  }

  // Stream defaults
  io.rx.ready := False
  io.tx.valid := False
  io.tx.payload := B(0, 8 bits)

  val isTxState = (state === DecoderState.TX_STATUS) || (state === DecoderState.TX_STAMP_RESULT)
  io.txEnable := isTxState

  // Pulses reset automatically
  regStampStart    := False
  regStampAbort    := False
  regStampIrqClear := False

  switch(state) {
    is(DecoderState.IDLE) {
      io.rx.ready := True
      when(io.rx.valid) {
        regOpcode := io.rx.payload.asUInt
        state     := DecoderState.LEN_MSB
      }
    }

    is(DecoderState.LEN_MSB) {
      io.rx.ready := True
      when(io.rx.valid) {
        regLenMsb := io.rx.payload
        state     := DecoderState.LEN_LSB
      }
    }

    is(DecoderState.LEN_LSB) {
      io.rx.ready := True
      when(io.rx.valid) {
        val totalLen = Cat(regLenMsb, io.rx.payload).asUInt
        regLen         := totalLen
        bytesRemaining := totalLen
        byteIndex      := 0

        when(totalLen === 0) {
          switch(regOpcode) {
            is(QspiOpcode.OP_STATUS) {
              state := DecoderState.TX_STATUS
            }
            is(QspiOpcode.OP_ABORT) {
              regStampAbort := True
              state         := DecoderState.IDLE
            }
            is(QspiOpcode.OP_IRQ_CLEAR) {
              regStampIrqClear := True
              state            := DecoderState.IDLE
            }
            is(QspiOpcode.OP_STAMP_READ) {
              // Latch snapshot of current results
              snapStatus    := statusFlagByte
              snapZeros     := io.stampWinningZeros
              snapNonce     := io.stampWinningNonce
              snapRounds    := io.stampRoundsEvaluated
              snapDigest    := io.stampWinningDigest
              snapCandidate := io.stampWinningCandidate
              state         := DecoderState.TX_STAMP_RESULT
            }
            default {
              state := DecoderState.IDLE
            }
          }
        } otherwise {
          switch(regOpcode) {
            is(QspiOpcode.OP_STAMP_GRIND) {
              state := DecoderState.RX_STAMP_PAYLOAD
            }
            default {
              state := DecoderState.RX_DISCARD
            }
          }
        }
      }
    }

    // RX Stamp payload: 89 bytes
    // [0]: targetCost (1B)
    // [1..32]: midstate (32B)
    // [33..64]: baseCandidate (32B)
    // [65..72]: totalLengthBits (8B)
    // [73..80]: startNonce (8B)
    // [81..88]: maxRounds (8B)
    is(DecoderState.RX_STAMP_PAYLOAD) {
      io.rx.ready := True
      when(io.rx.valid) {
        val b = io.rx.payload
        when(byteIndex === 0) {
          cfgTargetCost := b.asUInt
        } elsewhen(byteIndex >= 1 && byteIndex <= 32) {
          val offset = (byteIndex - 1).resize(8 bits)
          for (i <- 0 until 32) {
            when(offset === i) {
              val msb = 255 - i * 8
              val lsb = msb - 7
              cfgMidstate(msb downto lsb) := b
            }
          }
        } elsewhen(byteIndex >= 33 && byteIndex <= 64) {
          val offset = (byteIndex - 33).resize(8 bits)
          for (i <- 0 until 32) {
            when(offset === i) {
              val msb = 255 - i * 8
              val lsb = msb - 7
              cfgBaseCandidate(msb downto lsb) := b
            }
          }
        } elsewhen(byteIndex >= 65 && byteIndex <= 72) {
          val offset = (byteIndex - 65).resize(8 bits)
          for (i <- 0 until 8) {
            when(offset === i) {
              val msb = 63 - i * 8
              val lsb = msb - 7
              cfgTotalLengthBits(msb downto lsb) := b.asUInt
            }
          }
        } elsewhen(byteIndex >= 73 && byteIndex <= 80) {
          val offset = (byteIndex - 73).resize(8 bits)
          for (i <- 0 until 8) {
            when(offset === i) {
              val msb = 63 - i * 8
              val lsb = msb - 7
              cfgStartNonce(msb downto lsb) := b.asUInt
            }
          }
        } elsewhen(byteIndex >= 81 && byteIndex <= 88) {
          val offset = (byteIndex - 81).resize(8 bits)
          for (i <- 0 until 8) {
            when(offset === i) {
              val msb = 63 - i * 8
              val lsb = msb - 7
              cfgMaxRounds(msb downto lsb) := b.asUInt
            }
          }
        }

        byteIndex      := byteIndex + 1
        bytesRemaining := bytesRemaining - 1

        when(bytesRemaining === 1) {
          regStampStart := True
          state         := DecoderState.IDLE
        }
      }
    }

    is(DecoderState.RX_DISCARD) {
      io.rx.ready := True
      when(io.rx.valid) {
        bytesRemaining := bytesRemaining - 1
        when(bytesRemaining === 1) {
          state := DecoderState.IDLE
        }
      }
    }

    is(DecoderState.TX_STATUS) {
      when(byteIndex < 4) {
        io.tx.valid   := True
        io.tx.payload := statusBytes(byteIndex(1 downto 0))
        when(io.tx.ready) {
          byteIndex := byteIndex + 1
        }
      }
    }

    is(DecoderState.TX_STAMP_RESULT) {
      when(byteIndex < 82) {
        io.tx.valid   := True
        io.tx.payload := getResultByte(byteIndex)
        when(io.tx.ready) {
          byteIndex := byteIndex + 1
        }
      }
    }
  }

  // Reset to IDLE whenever Chip Select deasserts
  when(io.cs_n) {
    state := DecoderState.IDLE
  }

  // Connect outputs to Stamper
  io.stampStart           := regStampStart
  io.stampAbort           := regStampAbort
  io.stampIrqClear        := regStampIrqClear
  io.stampTargetCost      := cfgTargetCost
  io.stampMidstate        := cfgMidstate
  io.stampBaseCandidate   := cfgBaseCandidate
  io.stampTotalLengthBits := cfgTotalLengthBits
  io.stampStartNonce      := cfgStartNonce
  io.stampMaxRounds       := cfgMaxRounds
}
