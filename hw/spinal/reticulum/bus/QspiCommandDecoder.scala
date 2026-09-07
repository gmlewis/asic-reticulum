package reticulum.bus

import spinal.core._
import spinal.lib._

object QspiOpcode {
  val OP_STATUS      = 0x01
  val OP_ABORT       = 0x02
  val OP_IRQ_CLEAR   = 0x03
  val OP_STAMP_GRIND = 0x10
  val OP_STAMP_READ  = 0x11
  val OP_X25519_MULT = 0x20
  val OP_X25519_READ = 0x21
  val OP_TOKEN_SEAL  = 0x30
  val OP_TOKEN_OPEN  = 0x31
  val OP_TOKEN_READ  = 0x32
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

  // Control & Status lines to/from X25519Ladder
  val x25519Start    = out Bool()
  val x25519Abort    = out Bool()
  val x25519IrqClear = out Bool()
  val x25519Scalar   = out Bits(256 bits)
  val x25519UCoord   = out Bits(256 bits)

  val x25519Busy   = in Bool()
  val x25519Done   = in Bool()
  val x25519Irq    = in Bool()
  val x25519Result = in Bits(256 bits)

  // Control & Status lines to/from TokenEngine
  val tokenStart        = out Bool()
  val tokenMode         = out Bool()
  val tokenAbort        = out Bool()
  val tokenIrqClear     = out Bool()
  val tokenSignKey      = out Bits(128 bits)
  val tokenEncKey       = out Bits(128 bits)
  val tokenIv           = out Bits(128 bits)
  val tokenDataLen      = out UInt(16 bits)
  val tokenHostWrEn     = out Bool()
  val tokenHostWrAddr   = out UInt(10 bits)
  val tokenHostWrData   = out Bits(8 bits)
  val tokenHostRdAddr   = out UInt(10 bits)
  val tokenHostRdData   = in Bits(8 bits)

  val tokenBusy         = in Bool()
  val tokenDone         = in Bool()
  val tokenIrq          = in Bool()
  val tokenStatus       = in Bits(8 bits)
  val tokenResultLen    = in UInt(16 bits)
  val tokenResultOffset = in UInt(16 bits)
}

object DecoderState extends SpinalEnum {
  val IDLE, LEN_MSB, LEN_LSB, RX_STAMP_PAYLOAD, RX_X25519_PAYLOAD, RX_TOKEN_SEAL_PAYLOAD, RX_TOKEN_OPEN_PAYLOAD, RX_DISCARD, TX_STATUS, TX_STAMP_RESULT, TX_X25519_RESULT, TX_TOKEN_RESULT = newElement()
}

/**
 * Command Decoder FSM for Reticulum QSPI Host Interconnect.
 *
 * Framing: [OpCode: 1B] [Length: 2B big-endian] [Payload: Length bytes]
 */
case class QspiCommandDecoder() extends Component {
  val io = QspiCommandDecoderIo()

  val state = RegInit(DecoderState.IDLE)

  val regOpcode = Reg(UInt(8 bits)) init (0)
  val regLenMsb = Reg(Bits(8 bits)) init (0)
  val regLen    = Reg(UInt(16 bits)) init (0)

  val bytesRemaining = Reg(UInt(16 bits)) init (0)
  val byteIndex      = Reg(UInt(16 bits)) init (0)

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

  // X25519 latched configuration registers
  val cfgScalar         = Reg(Bits(256 bits)) init (0)
  val cfgUCoord         = Reg(Bits(256 bits)) init (0)
  val regX25519Start    = RegInit(False)
  val regX25519Abort    = RegInit(False)
  val regX25519IrqClear = RegInit(False)
  val snapX25519Result  = Reg(Bits(256 bits)) init (0)

  // Token latched configuration registers
  val cfgTokenSignKey  = Reg(Bits(128 bits)) init (0)
  val cfgTokenEncKey   = Reg(Bits(128 bits)) init (0)
  val cfgTokenIv       = Reg(Bits(128 bits)) init (0)
  val cfgTokenDataLen  = Reg(UInt(16 bits)) init (0)
  val cfgTokenMode     = Reg(Bool()) init (True)

  val regTokenStart    = RegInit(False)
  val regTokenAbort    = RegInit(False)
  val regTokenIrqClear = RegInit(False)

  val regTokenWrEn   = RegInit(False)
  val regTokenWrAddr = Reg(UInt(10 bits)) init (0)
  val regTokenWrData = Reg(Bits(8 bits)) init (0)

  val snapTokenStatus = Reg(Bits(8 bits)) init (0)
  val snapTokenLen    = Reg(UInt(16 bits)) init (0)
  val snapTokenOffset = Reg(UInt(16 bits)) init (0)

  // Status buffer for reading
  val statusBytes = Vec(Bits(8 bits), 4)
  val statusFlagByte = Cat(
    io.tokenIrq,
    io.x25519Irq,
    io.x25519Busy,
    io.x25519Done,
    io.stampIrq,
    io.stampBusy,
    io.stampMeetsTarget,
    io.stampDone
  )
  statusBytes(0) := statusFlagByte
  statusBytes(1) := Cat(B"6'b000100", io.tokenBusy, io.tokenDone) // Arch v1.0 (0x10) + token flags
  statusBytes(2) := io.stampRoundsEvaluated(15 downto 8).asBits
  statusBytes(3) := io.stampRoundsEvaluated(7 downto 0).asBits

  // Snapshot of winning stamp results for transmission
  val snapStatus    = Reg(Bits(8 bits)) init (0)
  val snapZeros     = Reg(UInt(8 bits)) init (0)
  val snapNonce     = Reg(UInt(64 bits)) init (0)
  val snapRounds    = Reg(UInt(64 bits)) init (0)
  val snapDigest    = Reg(Bits(256 bits)) init (0)
  val snapCandidate = Reg(Bits(256 bits)) init (0)

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

  def getX25519ResultByte(idx: UInt): Bits = {
    val b = Bits(8 bits)
    switch(idx) {
      for (i <- 0 until 32) {
        is(i) {
          val lsb = i * 8
          val msb = lsb + 7
          b := snapX25519Result(msb downto lsb)
        }
      }
      default { b := B(0, 8 bits) }
    }
    b
  }

  // Stream defaults
  io.rx.ready   := False
  io.tx.valid   := False
  io.tx.payload := B(0, 8 bits)

  val isTxState = (state === DecoderState.TX_STATUS) ||
                  (state === DecoderState.TX_STAMP_RESULT) ||
                  (state === DecoderState.TX_X25519_RESULT) ||
                  (state === DecoderState.TX_TOKEN_RESULT)
  io.txEnable := isTxState

  // Pulses reset automatically
  regStampStart    := False
  regStampAbort    := False
  regStampIrqClear := False
  regX25519Start   := False
  regX25519Abort   := False
  regX25519IrqClear := False
  regTokenStart    := False
  regTokenAbort    := False
  regTokenIrqClear := False
  regTokenWrEn     := False

  // Token read address calculation during TX_TOKEN_RESULT
  when(state === DecoderState.TX_TOKEN_RESULT && byteIndex >= 3) {
    io.tokenHostRdAddr := (snapTokenOffset + (byteIndex - 3)).resized
  } otherwise {
    io.tokenHostRdAddr := 0
  }

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
              regX25519Abort := True
              regTokenAbort := True
              state         := DecoderState.IDLE
            }
            is(QspiOpcode.OP_IRQ_CLEAR) {
              regStampIrqClear := True
              regX25519IrqClear := True
              regTokenIrqClear := True
              state            := DecoderState.IDLE
            }
            is(QspiOpcode.OP_STAMP_READ) {
              snapStatus    := statusFlagByte
              snapZeros     := io.stampWinningZeros
              snapNonce     := io.stampWinningNonce
              snapRounds    := io.stampRoundsEvaluated
              snapDigest    := io.stampWinningDigest
              snapCandidate := io.stampWinningCandidate
              state         := DecoderState.TX_STAMP_RESULT
            }
            is(QspiOpcode.OP_X25519_READ) {
              snapX25519Result := io.x25519Result
              state            := DecoderState.TX_X25519_RESULT
            }
            is(QspiOpcode.OP_TOKEN_READ) {
              snapTokenStatus := io.tokenStatus
              snapTokenLen    := io.tokenResultLen
              snapTokenOffset := io.tokenResultOffset
              state           := DecoderState.TX_TOKEN_RESULT
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
            is(QspiOpcode.OP_X25519_MULT) {
              state := DecoderState.RX_X25519_PAYLOAD
            }
            is(QspiOpcode.OP_TOKEN_SEAL) {
              cfgTokenMode := True
              state        := DecoderState.RX_TOKEN_SEAL_PAYLOAD
            }
            is(QspiOpcode.OP_TOKEN_OPEN) {
              cfgTokenMode := False
              state        := DecoderState.RX_TOKEN_OPEN_PAYLOAD
            }
            default {
              state := DecoderState.RX_DISCARD
            }
          }
        }
      }
    }

    // RX Stamp payload: 89 bytes
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

    // RX X25519 payload: 64 bytes (32B scalar + 32B u-coord in little-endian order)
    is(DecoderState.RX_X25519_PAYLOAD) {
      io.rx.ready := True
      when(io.rx.valid) {
        val b = io.rx.payload
        when(byteIndex <= 31) {
          val offset = byteIndex.resize(8 bits)
          for (i <- 0 until 32) {
            when(offset === i) {
              val lsb = i * 8
              val msb = lsb + 7
              cfgScalar(msb downto lsb) := b
            }
          }
        } elsewhen(byteIndex >= 32 && byteIndex <= 63) {
          val offset = (byteIndex - 32).resize(8 bits)
          for (i <- 0 until 32) {
            when(offset === i) {
              val lsb = i * 8
              val msb = lsb + 7
              cfgUCoord(msb downto lsb) := b
            }
          }
        }

        byteIndex      := byteIndex + 1
        bytesRemaining := bytesRemaining - 1

        when(bytesRemaining === 1) {
          regX25519Start := True
          state          := DecoderState.IDLE
        }
      }
    }

    // RX Token Seal payload: [16B signKey] [16B encKey] [16B IV] [dataLen Plaintext]
    is(DecoderState.RX_TOKEN_SEAL_PAYLOAD) {
      io.rx.ready := True
      when(io.rx.valid) {
        val b = io.rx.payload
        when(byteIndex <= 15) {
          val offset = byteIndex.resize(4 bits)
          for (i <- 0 until 16) {
            when(offset === i) {
              val msb = 127 - i * 8
              val lsb = msb - 7
              cfgTokenSignKey(msb downto lsb) := b
            }
          }
        } elsewhen(byteIndex >= 16 && byteIndex <= 31) {
          val offset = (byteIndex - 16).resize(4 bits)
          for (i <- 0 until 16) {
            when(offset === i) {
              val msb = 127 - i * 8
              val lsb = msb - 7
              cfgTokenEncKey(msb downto lsb) := b
            }
          }
        } elsewhen(byteIndex >= 32 && byteIndex <= 47) {
          val offset = (byteIndex - 32).resize(4 bits)
          for (i <- 0 until 16) {
            when(offset === i) {
              val msb = 127 - i * 8
              val lsb = msb - 7
              cfgTokenIv(msb downto lsb) := b
            }
          }
        } otherwise {
          // Write plaintext byte into Token buffer memory starting at offset 16
          regTokenWrEn   := True
          regTokenWrAddr := (U(16, 10 bits) + (byteIndex - 48).resized).resized
          regTokenWrData := b
        }

        byteIndex      := byteIndex + 1
        bytesRemaining := bytesRemaining - 1

        when(bytesRemaining === 1) {
          val pLen = (regLen >= 48) ? (regLen - 48) | U(0, 16 bits)
          cfgTokenDataLen := pLen
          regTokenStart   := True
          state           := DecoderState.IDLE
        }
      }
    }

    // RX Token Open payload: [16B signKey] [16B encKey] [tokenLen TokenBytes]
    is(DecoderState.RX_TOKEN_OPEN_PAYLOAD) {
      io.rx.ready := True
      when(io.rx.valid) {
        val b = io.rx.payload
        when(byteIndex <= 15) {
          val offset = byteIndex.resize(4 bits)
          for (i <- 0 until 16) {
            when(offset === i) {
              val msb = 127 - i * 8
              val lsb = msb - 7
              cfgTokenSignKey(msb downto lsb) := b
            }
          }
        } elsewhen(byteIndex >= 16 && byteIndex <= 31) {
          val offset = (byteIndex - 16).resize(4 bits)
          for (i <- 0 until 16) {
            when(offset === i) {
              val msb = 127 - i * 8
              val lsb = msb - 7
              cfgTokenEncKey(msb downto lsb) := b
            }
          }
        } otherwise {
          // Write token byte into Token buffer memory starting at offset 0
          regTokenWrEn   := True
          regTokenWrAddr := (byteIndex - 32).resized
          regTokenWrData := b
        }

        byteIndex      := byteIndex + 1
        bytesRemaining := bytesRemaining - 1

        when(bytesRemaining === 1) {
          val tLen = (regLen >= 32) ? (regLen - 32) | U(0, 16 bits)
          cfgTokenDataLen := tLen
          regTokenStart   := True
          state           := DecoderState.IDLE
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

    is(DecoderState.TX_X25519_RESULT) {
      when(byteIndex < 32) {
        io.tx.valid   := True
        io.tx.payload := getX25519ResultByte(byteIndex)
        when(io.tx.ready) {
          byteIndex := byteIndex + 1
        }
      }
    }

    is(DecoderState.TX_TOKEN_RESULT) {
      val totalTxBytes = U(3, 16 bits) + snapTokenLen
      when(byteIndex < totalTxBytes) {
        io.tx.valid := True
        switch(byteIndex) {
          is(0) { io.tx.payload := snapTokenStatus }
          is(1) { io.tx.payload := snapTokenLen(15 downto 8).asBits }
          is(2) { io.tx.payload := snapTokenLen(7 downto 0).asBits }
          default {
            io.tx.payload := io.tokenHostRdData
          }
        }
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

  // Connect outputs to X25519Ladder
  io.x25519Start    := regX25519Start
  io.x25519Abort    := regX25519Abort
  io.x25519IrqClear := regX25519IrqClear
  io.x25519Scalar   := cfgScalar
  io.x25519UCoord   := cfgUCoord

  // Connect outputs to TokenEngine
  io.tokenStart        := regTokenStart
  io.tokenMode         := cfgTokenMode
  io.tokenAbort        := regTokenAbort
  io.tokenIrqClear     := regTokenIrqClear
  io.tokenSignKey      := cfgTokenSignKey
  io.tokenEncKey       := cfgTokenEncKey
  io.tokenIv           := cfgTokenIv
  io.tokenDataLen      := cfgTokenDataLen
  io.tokenHostWrEn     := regTokenWrEn
  io.tokenHostWrAddr   := regTokenWrAddr
  io.tokenHostWrData   := regTokenWrData
}
