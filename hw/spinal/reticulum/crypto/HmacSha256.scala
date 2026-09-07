package reticulum.crypto

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.core.sim._

/**
 * RFC 2104 / FIPS 198-1 HMAC-SHA256 hardware acceleration core.
 *
 * Reuses Sha256Pipe for inner and outer hashing.
 * Streaming byte interface accepts messages of arbitrary length up to 1024+ bytes.
 * Key lengths up to 64 bytes (512 bits) supported.
 */
case class HmacSha256() extends Component {
  val io = new Bundle {
    val key      = in Bits(512 bits) // Up to 64 bytes, MSB aligned (byte 0 at bits 511..504)
    val keyLen   = in UInt(6 bits)   // Number of valid key bytes (1..64)
    val start    = in Bool()         // Pulse to begin HMAC operation

    val msgByte  = in Bits(8 bits)   // Streaming message byte
    val msgValid = in Bool()         // Valid byte strobe
    val msgLast  = in Bool()         // True if this is the final byte of the message
    val msgReady = out Bool()        // Ready to receive message byte

    val hmac     = out Bits(256 bits)
    val done     = out Bool()
    val busy     = out Bool()
  }

  val pipe = Sha256Pipe(roundsPerStage = 1, tagWidth = 8)
  pipe.io.rsp.ready := True




  // State registers
  val innerMidstate = Reg(Bits(256 bits)).init(0).simPublic()
  val outerMidstate = Reg(Bits(256 bits)).init(0).simPublic()
  val runningState  = Reg(Bits(256 bits)).init(0).simPublic()
  val innerDigest   = Reg(Bits(256 bits)).init(0).simPublic()
  val hmacResult    = Reg(Bits(256 bits)) init(0)
  val doneReg       = RegInit(False)

  val totalMsgBytes = Reg(UInt(16 bits)) init(0)
  val byteInBlock   = Reg(UInt(6 bits)) init(0)
  val lastBlockLen  = Reg(UInt(7 bits)) init(0)
  val blockBuf      = Vec(Reg(Bits(8 bits)) init(0), 64)
  val extraPadBlock = Reg(Bits(512 bits)) init(0)
  val finalPadBlock = Reg(Bits(512 bits)) init(0)

  io.hmac := hmacResult
  io.done := doneReg

  def packBlock(buf: Vec[Bits]): Bits = {
    val res = Bits(512 bits)
    for (i <- 0 until 64) {
      val msb = 511 - i * 8
      val lsb = msb - 7
      res(msb downto lsb) := buf(i)
    }
    res
  }

  // Precomputed ipad and opad blocks
  val ipadBlock = Bits(512 bits)
  val opadBlock = Bits(512 bits)
  for (i <- 0 until 64) {
    val msb = 511 - i * 8
    val lsb = msb - 7
    val kByte = Mux(U(i) < io.keyLen, io.key(msb downto lsb), B(0, 8 bits))
    ipadBlock(msb downto lsb) := kByte ^ B(0x36, 8 bits)
    opadBlock(msb downto lsb) := kByte ^ B(0x5c, 8 bits)
  }

  val TAG_IPAD        = B(1, 8 bits)
  val TAG_OPAD        = B(2, 8 bits)
  val TAG_MSG_BLOCK   = B(3, 8 bits)
  val TAG_MSG_FINAL   = B(4, 8 bits)
  val TAG_OUTER_FINAL = B(5, 8 bits)

  // Combinational padded block builders
  val totalBits = (U(64, 64 bits) + totalMsgBytes.resize(64)) |<< 3
  val pad1BlockComb = Bits(512 bits)
  val pad2BlockComb = Bits(512 bits)
  for (i <- 0 until 64) {
    val msb = 511 - i * 8
    val lsb = msb - 7

    if (i >= 56) {
      val lenByte = i - 56
      val lMsb = 63 - lenByte * 8
      val lLsb = lMsb - 7

      when(lastBlockLen < 56) {
        when(U(i) < lastBlockLen) {
          pad1BlockComb(msb downto lsb) := blockBuf(i)
        } elsewhen(U(i) === lastBlockLen) {
          pad1BlockComb(msb downto lsb) := B(0x80, 8 bits)
        } otherwise {
          pad1BlockComb(msb downto lsb) := totalBits(lMsb downto lLsb).asBits
        }
      } otherwise {
        when(U(i) < lastBlockLen) {
          pad1BlockComb(msb downto lsb) := blockBuf(i)
        } elsewhen(U(i) === lastBlockLen) {
          pad1BlockComb(msb downto lsb) := B(0x80, 8 bits)
        } otherwise {
          pad1BlockComb(msb downto lsb) := B(0x00, 8 bits)
        }
      }

      pad2BlockComb(msb downto lsb) := totalBits(lMsb downto lLsb).asBits
    } else {
      when(U(i) < lastBlockLen) {
        pad1BlockComb(msb downto lsb) := blockBuf(i)
      } elsewhen(U(i) === lastBlockLen) {
        pad1BlockComb(msb downto lsb) := B(0x80, 8 bits)
      } otherwise {
        pad1BlockComb(msb downto lsb) := B(0x00, 8 bits)
      }

      when(lastBlockLen === 64 && U(i) === 0) {
        pad2BlockComb(msb downto lsb) := B(0x80, 8 bits)
      } otherwise {
        pad2BlockComb(msb downto lsb) := B(0x00, 8 bits)
      }
    }
  }

  // Default pipe command assignments
  pipe.io.cmd.valid               := False
  pipe.io.cmd.payload.block       := 0
  pipe.io.cmd.payload.useMidstate := False
  pipe.io.cmd.payload.midstate    := 0
  pipe.io.cmd.payload.tag         := 0

  val fsm = new StateMachine {
    val sIdle: State            = new State with EntryPoint
    val sSendIpad: State        = new State
    val sSendOpad: State        = new State
    val sWaitKeyMidstate: State = new State
    val sMsgStream: State       = new State
    val sSendMsgBlock: State    = new State
    val sWaitMsgBlock: State    = new State
    val sPadMsg: State          = new State
    val sSendPad1: State        = new State
    val sWaitPad1ForPad2: State = new State
    val sSendPad2: State        = new State
    val sWaitPad1: State        = new State
    val sSendOuter: State       = new State
    val sWaitOuter: State       = new State

    io.busy     := !isActive(sIdle)
    io.msgReady := isActive(sMsgStream)

    sIdle.whenIsActive {
      doneReg := False
      when(io.start) {
        totalMsgBytes := 0
        byteInBlock   := 0
        lastBlockLen  := 0
        for (i <- 0 until 64) {
          blockBuf(i) := 0
        }
        goto(sSendIpad)
      }
    }

    sSendIpad.whenIsActive {
      pipe.io.cmd.valid               := True
      pipe.io.cmd.payload.block       := ipadBlock
      pipe.io.cmd.payload.useMidstate := False
      pipe.io.cmd.payload.tag         := TAG_IPAD
      when(pipe.io.cmd.fire) {
        goto(sSendOpad)
      }
    }

    sSendOpad.whenIsActive {
      pipe.io.cmd.valid               := True
      pipe.io.cmd.payload.block       := opadBlock
      pipe.io.cmd.payload.useMidstate := False
      pipe.io.cmd.payload.tag         := TAG_OPAD
      when(pipe.io.cmd.fire) {
        goto(sWaitKeyMidstate)
      }
    }

    sWaitKeyMidstate.whenIsActive {
      when(pipe.io.rsp.valid) {
        when(pipe.io.rsp.tag === TAG_IPAD) {
          innerMidstate := pipe.io.rsp.digest
          runningState  := pipe.io.rsp.digest
        }
        when(pipe.io.rsp.tag === TAG_OPAD) {
          outerMidstate := pipe.io.rsp.digest
          goto(sMsgStream)
        }
      }
    }

    sMsgStream.whenIsActive {
      when(io.msgValid) {
        blockBuf(byteInBlock) := io.msgByte
        
        totalMsgBytes         := totalMsgBytes + 1

        when(io.msgLast) {
          lastBlockLen := (byteInBlock.resize(7) + 1)
          goto(sPadMsg)
        } elsewhen(byteInBlock === 63) {
          byteInBlock := 0
          goto(sSendMsgBlock)
        } otherwise {
          byteInBlock := byteInBlock + 1
        }
      } elsewhen(io.msgLast) {
        // Empty message (msgLast without msgValid)
        lastBlockLen := byteInBlock.resize(7)
        goto(sPadMsg)
      }
    }

    sSendMsgBlock.whenIsActive {
      pipe.io.cmd.valid               := True
      pipe.io.cmd.payload.block       := packBlock(blockBuf)
      pipe.io.cmd.payload.useMidstate := True
      pipe.io.cmd.payload.midstate    := runningState
      pipe.io.cmd.payload.tag         := TAG_MSG_BLOCK
      when(pipe.io.cmd.fire) {
        goto(sWaitMsgBlock)
      }
    }

    sWaitMsgBlock.whenIsActive {
      when(pipe.io.rsp.valid && pipe.io.rsp.tag === TAG_MSG_BLOCK) {
        runningState := pipe.io.rsp.digest
        for (i <- 0 until 64) {
          blockBuf(i) := 0
        }
        goto(sMsgStream)
      }
    }

    sPadMsg.whenIsActive {
      finalPadBlock := pad1BlockComb
      extraPadBlock := pad2BlockComb
      goto(sSendPad1)
    }

    sSendPad1.whenIsActive {
      pipe.io.cmd.valid               := True
      pipe.io.cmd.payload.block       := finalPadBlock
      pipe.io.cmd.payload.useMidstate := True
      pipe.io.cmd.payload.midstate    := runningState
      pipe.io.cmd.payload.tag         := Mux(lastBlockLen < 56, TAG_MSG_FINAL, TAG_MSG_BLOCK)
      when(pipe.io.cmd.fire) {
        when(lastBlockLen < 56) {
          goto(sWaitPad1)
        } otherwise {
          goto(sWaitPad1ForPad2)
        }
      }
    }

    sWaitPad1ForPad2.whenIsActive {
      when(pipe.io.rsp.valid && pipe.io.rsp.tag === TAG_MSG_BLOCK) {
        runningState := pipe.io.rsp.digest
        goto(sSendPad2)
      }
    }

    sSendPad2.whenIsActive {
      pipe.io.cmd.valid               := True
      pipe.io.cmd.payload.block       := extraPadBlock
      pipe.io.cmd.payload.useMidstate := True
      pipe.io.cmd.payload.midstate    := runningState
      pipe.io.cmd.payload.tag         := TAG_MSG_FINAL
      when(pipe.io.cmd.fire) {
        goto(sWaitPad1)
      }
    }

    sWaitPad1.whenIsActive {
      when(pipe.io.rsp.valid && pipe.io.rsp.tag === TAG_MSG_FINAL) {
        innerDigest := pipe.io.rsp.digest
        goto(sSendOuter)
      }
    }

    sSendOuter.whenIsActive {
      // Outer block: innerDigest (32B) || 0x80 || 23 zeros || 64-bit length (96 * 8 = 768)
      val outerBlock = Bits(512 bits)
      outerBlock(511 downto 256) := innerDigest
      outerBlock(255 downto 248) := B(0x80, 8 bits)
      outerBlock(247 downto 64)  := 0
      outerBlock(63 downto 0)    := B(768, 64 bits)

      pipe.io.cmd.valid               := True
      pipe.io.cmd.payload.block       := outerBlock
      pipe.io.cmd.payload.useMidstate := True
      pipe.io.cmd.payload.midstate    := outerMidstate
      pipe.io.cmd.payload.tag         := TAG_OUTER_FINAL
      when(pipe.io.cmd.fire) {
        goto(sWaitOuter)
      }
    }

    sWaitOuter.whenIsActive {
      when(pipe.io.rsp.valid && pipe.io.rsp.tag === TAG_OUTER_FINAL) {
        hmacResult := pipe.io.rsp.digest
        doneReg    := True
        goto(sIdle)
      }
    }
  }
}

/**
 * Companion object for generating Verilog.
 */
object HmacSha256Verilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )
  config.generateVerilog(HmacSha256()).printPruned()
  println("Successfully generated Verilog in hw/gen/HmacSha256.v")
}
