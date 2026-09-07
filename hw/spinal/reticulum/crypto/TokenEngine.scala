package reticulum.crypto

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._

object TokenStatus {
  def OK       = B"8'h00"
  def ERR_HMAC = B"8'h01"
  def ERR_PAD  = B"8'h02"
  def ERR_LEN  = B"8'h03"
}

/**
 * Hardware Acceleration Engine for Reticulum crypto.Token (AES-128-CBC + HMAC-SHA256).
 *
 * Implements:
 *  - Token Seal: PKCS#7 padding -> AES-128-CBC Encrypt -> HMAC-SHA256 Signing
 *  - Token Open: Constant-time HMAC Verify -> AES-128-CBC Decrypt -> PKCS#7 Unpadding & Validation
 *
 * Memory Layout for Seal:
 *  - Input: Plaintext placed at mem[16 .. 16 + dataLen - 1].
 *  - Output: [IV: 16B at 0..15] [Ciphertext: PaddedLen at 16 .. 16 + PaddedLen - 1] [HMAC: 32B at 16 + PaddedLen .. 47 + PaddedLen].
 *  - Total output length: 48 + PaddedLen, offset: 0.
 *
 * Memory Layout for Open:
 *  - Input: Token placed at mem[0 .. dataLen - 1] (IV: 16B, CT: dataLen - 48, HMAC: 32B).
 *  - Output: Plaintext at mem[16 .. 16 + resultLen - 1].
 *  - Total output length: resultLen, offset: 16.
 *
 * @param numEngines Number of parallel execution units (default 1).
 * @param bufferSize Internal packet buffer size in bytes (default 1024).
 */
case class TokenEngine(numEngines: Int = 1, bufferSize: Int = 1024) extends Component {
  val io = new Bundle {
    // Control lines
    val start        = in Bool()
    val mode         = in Bool() // True = Seal (encrypt+sign), False = Open (verify+decrypt)
    val abort        = in Bool()
    val irqClear     = in Bool()
    val busy         = out Bool()
    val done         = out Bool()
    val irq          = out Bool()
    val status       = out Bits(8 bits) // 0x00 = OK, 0x01 = ERR_HMAC, 0x02 = ERR_PAD, 0x03 = ERR_LEN

    // Keys & Parameters
    val signKey      = in Bits(128 bits) // 16-byte HMAC signing key (tokenKey[0..15])
    val encKey       = in Bits(128 bits) // 16-byte AES encryption key (tokenKey[16..31])
    val iv           = in Bits(128 bits) // 16-byte initialization vector
    val dataLen      = in UInt(16 bits)  // Input data length in bytes
    val resultLen    = out UInt(16 bits) // Resulting data length in bytes
    val resultOffset = out UInt(16 bits) // Offset in buffer where result begins (0 for Seal, 16 for Open)

    // Host buffer memory interface (for QSPI read/write)
    val hostWrEn     = in Bool()
    val hostWrAddr   = in UInt(10 bits)
    val hostWrData   = in Bits(8 bits)
    val hostRdAddr   = in UInt(10 bits)
    val hostRdData   = out Bits(8 bits)
  }

  // Internal unified packet memory (1024 bytes)
  val mem = Mem(Bits(8 bits), bufferSize)

  // Internal subcomponents
  val aes  = AesCore()
  val hmac = HmacSha256()

  // State registers
  val regDone         = RegInit(False)
  val regIrq          = RegInit(False)
  val regStatus       = Reg(Bits(8 bits)) init(TokenStatus.OK)
  val regResultLen    = Reg(UInt(16 bits)) init(0)
  val regResultOffset = Reg(UInt(16 bits)) init(0)

  val cfgMode    = Reg(Bool()) init(True)
  val cfgDataLen = Reg(UInt(16 bits)) init(0)
  val cfgSignKey = Reg(Bits(128 bits)) init(0)
  val cfgEncKey  = Reg(Bits(128 bits)) init(0)
  val cfgIv      = Reg(Bits(128 bits)) init(0)

  val paddedLen    = Reg(UInt(16 bits)) init(0)
  val numBlocks    = Reg(UInt(16 bits)) init(0)
  val curBlock     = Reg(UInt(16 bits)) init(0)
  val chainIv      = Reg(Bits(128 bits)) init(0)
  val chainIvBytes = Vec(Reg(Bits(8 bits)) init(0), 16)
  val tempBytes    = Vec(Reg(Bits(8 bits)) init(0), 16)
  val regHmacBytes = Vec(Reg(Bits(8 bits)) init(0), 32)
  val byteCnt      = Reg(UInt(16 bits)) init(0)
  val padVal       = Reg(UInt(8 bits)) init(0)
  val hmacFail     = RegInit(False)

  io.done         := regDone
  io.irq          := regIrq
  io.status       := regStatus
  io.resultLen    := regResultLen
  io.resultOffset := regResultOffset

  // Host write port
  when(io.hostWrEn) {
    mem.write(io.hostWrAddr, io.hostWrData)
  }

  // Host read port (asynchronous read)
  io.hostRdData := mem.readAsync(io.hostRdAddr)

  // Pack 16 tempBytes into 128-bit block (byte 0 at MSB 127..120)
  val tempBlockIn = Bits(128 bits)
  for (i <- 0 until 16) {
    tempBlockIn(127 - i * 8 downto 120 - i * 8) := tempBytes(i)
  }

  // Extract IV bytes from cfgIv
  val ivBytes = Vec(Bits(8 bits), 16)
  for (i <- 0 until 16) {
    ivBytes(i) := cfgIv(127 - i * 8 downto 120 - i * 8)
  }

  // Pack chainIv from chainIvBytes
  val chainIvFromBytes = Bits(128 bits)
  for (i <- 0 until 16) {
    chainIvFromBytes(127 - i * 8 downto 120 - i * 8) := chainIvBytes(i)
  }

  // Default AES & HMAC inputs
  aes.io.key     := cfgEncKey
  aes.io.blockIn := 0
  aes.io.enc     := True
  aes.io.loadKey := False
  aes.io.start   := False

  hmac.io.key      := cfgSignKey ## B(0, 384 bits)
  hmac.io.keyLen   := 16
  hmac.io.start    := False
  hmac.io.msgByte  := 0
  hmac.io.msgValid := False
  hmac.io.msgLast  := False

  val fsm = new StateMachine {
    val sIdle: State            = new State with EntryPoint
    val sInit: State            = new State
    val sSealWriteIv: State     = new State
    val sSealPad: State         = new State
    val sLoadAesKey: State      = new State
    val sWaitAesKey: State      = new State

    // Seal states
    val sSealAesRead: State     = new State
    val sSealAesStart: State    = new State
    val sSealAesWait: State     = new State
    val sSealAesWrite: State    = new State
    val sSealHmacStart: State   = new State
    val sSealHmacStream: State  = new State
    val sSealHmacWait: State    = new State
    val sSealHmacWrite: State   = new State

    // Open states
    val sOpenHmacStart: State   = new State
    val sOpenHmacStream: State  = new State
    val sOpenHmacWait: State    = new State
    val sOpenHmacCheck: State   = new State
    val sOpenLoadIv: State      = new State
    val sOpenAesRead: State     = new State
    val sOpenAesStart: State    = new State
    val sOpenAesWait: State     = new State
    val sOpenAesWrite: State    = new State
    val sOpenCheckPad: State    = new State

    io.busy := !isActive(sIdle)

    sIdle.whenIsActive {
      when(io.start) {
        cfgMode    := io.mode
        cfgDataLen := io.dataLen
        cfgSignKey := io.signKey
        cfgEncKey  := io.encKey
        cfgIv      := io.iv

        regDone   := False
        regStatus := TokenStatus.OK
        goto(sInit)
      }
    }

    sInit.whenIsActive {
      when(cfgMode) {
        // PKCS#7 pad length calculation: padLen = 16 - (L % 16)
        val rem = (cfgDataLen & 15).resize(5)
        val pLen = 16 - rem
        val totPadded = cfgDataLen + pLen.resize(16)
        paddedLen := totPadded
        padVal    := pLen.asBits.asUInt.resized
        byteCnt   := 0
        goto(sSealWriteIv)
      } otherwise {
        // Open: verify length >= 48 and multiple of 16
        when(cfgDataLen < 48 || (cfgDataLen(3 downto 0) =/= 0)) {
          regStatus    := TokenStatus.ERR_LEN
          regResultLen := 0
          regDone      := True
          regIrq       := True
          goto(sIdle)
        } otherwise {
          // Compute CT length: dataLen - 48
          val ctLen = cfgDataLen - 48
          numBlocks := ctLen |>> 4
          goto(sOpenHmacStart)
        }
      }
    }

    // -------------------------------------------------------------
    // SEAL PIPELINE
    // -------------------------------------------------------------
    sSealWriteIv.whenIsActive {
      // Write IV byte into mem[byteCnt]
      mem.write(byteCnt.resized, ivBytes(byteCnt(3 downto 0)))
      when(byteCnt === 15) {
        byteCnt := cfgDataLen
        goto(sSealPad)
      } otherwise {
        byteCnt := byteCnt + 1
      }
    }

    sSealPad.whenIsActive {
      when(byteCnt < paddedLen) {
        // Write PKCS#7 pad byte into mem[16 + byteCnt]
        mem.write((U(16, 10 bits) + byteCnt.resized).resized, padVal.asBits)
        byteCnt := byteCnt + 1
      } otherwise {
        numBlocks := paddedLen |>> 4
        curBlock  := 0
        chainIv   := cfgIv
        goto(sLoadAesKey)
      }
    }

    sLoadAesKey.whenIsActive {
      when(!cfgMode) {
        chainIv := chainIvFromBytes
      }
      aes.io.loadKey := True
      goto(sWaitAesKey)
    }

    sWaitAesKey.whenIsActive {
      when(aes.io.keyReady) {
        byteCnt := 0
        when(cfgMode) {
          goto(sSealAesRead)
        } otherwise {
          goto(sOpenAesRead)
        }
      }
    }

    sSealAesRead.whenIsActive {
      // Read 16 bytes for curBlock from mem[16 + curBlock*16 + byteCnt]
      val addr = (U(16, 10 bits) + (curBlock |<< 4).resized + byteCnt.resized).resized
      val b = mem.readAsync(addr)
      tempBytes(byteCnt(3 downto 0)) := b

      when(byteCnt === 15) {
        goto(sSealAesStart)
      } otherwise {
        byteCnt := byteCnt + 1
      }
    }

    sSealAesStart.whenIsActive {
      aes.io.enc     := True
      aes.io.blockIn := tempBlockIn ^ chainIv
      aes.io.start   := True
      goto(sSealAesWait)
    }

    sSealAesWait.whenIsActive {
      when(aes.io.done) {
        chainIv := aes.io.blockOut
        for (i <- 0 until 16) {
          tempBytes(i) := aes.io.blockOut(127 - i * 8 downto 120 - i * 8)
        }
        byteCnt := 0
        goto(sSealAesWrite)
      }
    }

    sSealAesWrite.whenIsActive {
      // Write encrypted block back to mem[16 + curBlock*16 + byteCnt]
      val addr = (U(16, 10 bits) + (curBlock |<< 4).resized + byteCnt.resized).resized
      mem.write(addr, tempBytes(byteCnt(3 downto 0)))

      when(byteCnt === 15) {
        when(curBlock + 1 === numBlocks) {
          goto(sSealHmacStart)
        } otherwise {
          curBlock := curBlock + 1
          byteCnt  := 0
          goto(sSealAesRead)
        }
      } otherwise {
        byteCnt := byteCnt + 1
      }
    }

    sSealHmacStart.whenIsActive {
      hmac.io.start := True
      byteCnt       := 0
      goto(sSealHmacStream)
    }

    sSealHmacStream.whenIsActive {
      val totalHmacBytes = (U(16, 16 bits) + paddedLen)
      val b = mem.readAsync(byteCnt.resized)

      when(hmac.io.msgReady) {
        hmac.io.msgByte  := b
        hmac.io.msgValid := True
        hmac.io.msgLast  := (byteCnt + 1 === totalHmacBytes)

        when(byteCnt + 1 === totalHmacBytes) {
          goto(sSealHmacWait)
        } otherwise {
          byteCnt := byteCnt + 1
        }
      }
    }

    sSealHmacWait.whenIsActive {
      when(hmac.io.done) {
        for (i <- 0 until 32) {
          regHmacBytes(i) := hmac.io.hmac(255 - i * 8 downto 248 - i * 8)
        }
        byteCnt := 0
        goto(sSealHmacWrite)
      }
    }

    sSealHmacWrite.whenIsActive {
      // Write 32-byte HMAC to mem[16 + paddedLen + byteCnt]
      val addr = (U(16, 10 bits) + paddedLen.resized + byteCnt.resized).resized
      mem.write(addr, regHmacBytes(byteCnt(4 downto 0)))

      when(byteCnt === 31) {
        regResultLen    := U(48, 16 bits) + paddedLen
        regResultOffset := 0
        regStatus       := TokenStatus.OK
        regDone         := True
        regIrq          := True
        goto(sIdle)
      } otherwise {
        byteCnt := byteCnt + 1
      }
    }

    // -------------------------------------------------------------
    // OPEN PIPELINE
    // -------------------------------------------------------------
    sOpenHmacStart.whenIsActive {
      hmac.io.start := True
      byteCnt       := 0
      goto(sOpenHmacStream)
    }

    sOpenHmacStream.whenIsActive {
      val signedLen = cfgDataLen - 32
      val b = mem.readAsync(byteCnt.resized)

      when(hmac.io.msgReady) {
        hmac.io.msgByte  := b
        hmac.io.msgValid := True
        hmac.io.msgLast  := (byteCnt + 1 === signedLen)

        when(byteCnt + 1 === signedLen) {
          goto(sOpenHmacWait)
        } otherwise {
          byteCnt := byteCnt + 1
        }
      }
    }

    sOpenHmacWait.whenIsActive {
      when(hmac.io.done) {
        for (i <- 0 until 32) {
          regHmacBytes(i) := hmac.io.hmac(255 - i * 8 downto 248 - i * 8)
        }
        byteCnt  := 0
        hmacFail := False
        goto(sOpenHmacCheck)
      }
    }

    sOpenHmacCheck.whenIsActive {
      // Compare computed HMAC with mem[cfgDataLen - 32 + byteCnt]
      val addr = (cfgDataLen - 32 + byteCnt).resized
      val expByte = mem.readAsync(addr)
      val compByte = regHmacBytes(byteCnt(4 downto 0))

      when(expByte =/= compByte) {
        hmacFail := True
      }

      when(byteCnt === 31) {
        when(hmacFail || (expByte =/= compByte)) {
          regStatus    := TokenStatus.ERR_HMAC
          regResultLen := 0
          regDone      := True
          regIrq       := True
          goto(sIdle)
        } otherwise {
          // HMAC verified! Read initial IV from mem[0..15]
          byteCnt := 0
          goto(sOpenLoadIv)
        }
      } otherwise {
        byteCnt := byteCnt + 1
      }
    }

    sOpenLoadIv.whenIsActive {
      chainIvBytes(byteCnt(3 downto 0)) := mem.readAsync(byteCnt.resized)
      when(byteCnt === 15) {
        curBlock := 0
        goto(sLoadAesKey)
      } otherwise {
        byteCnt := byteCnt + 1
      }
    }

    sOpenAesRead.whenIsActive {
      // Read 16 bytes of ciphertext from mem[16 + curBlock*16 + byteCnt]
      val addr = (U(16, 10 bits) + (curBlock |<< 4).resized + byteCnt.resized).resized
      val b = mem.readAsync(addr)
      tempBytes(byteCnt(3 downto 0)) := b

      when(byteCnt === 15) {
        goto(sOpenAesStart)
      } otherwise {
        byteCnt := byteCnt + 1
      }
    }

    sOpenAesStart.whenIsActive {
      aes.io.enc     := False
      aes.io.blockIn := tempBlockIn
      aes.io.start   := True
      goto(sOpenAesWait)
    }

    sOpenAesWait.whenIsActive {
      when(aes.io.done) {
        val pt = aes.io.blockOut ^ chainIv
        chainIv := tempBlockIn // Next IV is current ciphertext block
        for (i <- 0 until 16) {
          tempBytes(i) := pt(127 - i * 8 downto 120 - i * 8)
        }
        byteCnt := 0
        goto(sOpenAesWrite)
      }
    }

    sOpenAesWrite.whenIsActive {
      // Write decrypted plaintext back to mem[16 + curBlock*16 + byteCnt]
      val addr = (U(16, 10 bits) + (curBlock |<< 4).resized + byteCnt.resized).resized
      mem.write(addr, tempBytes(byteCnt(3 downto 0)))

      when(byteCnt === 15) {
        when(curBlock + 1 === numBlocks) {
          // Decryption complete! Check PKCS#7 padding
          padVal := tempBytes(15).asUInt
          goto(sOpenCheckPad)
        } otherwise {
          curBlock := curBlock + 1
          byteCnt  := 0
          goto(sOpenAesRead)
        }
      } otherwise {
        byteCnt := byteCnt + 1
      }
    }

    sOpenCheckPad.whenIsActive {
      val ctLen = numBlocks |<< 4
      when(padVal === 0 || padVal > 16 || padVal.resize(16) > ctLen) {
        regStatus    := TokenStatus.ERR_PAD
        regResultLen := 0
        regDone      := True
        regIrq       := True
        goto(sIdle)
      } otherwise {
        // Verify all padding bytes equal padVal
        val padMismatch = False
        for (i <- 1 to 16) {
          when(U(i) <= padVal) {
            val b = tempBytes(16 - i)
            when(b.asUInt =/= padVal) {
              padMismatch := True
            }
          }
        }

        when(padMismatch) {
          regStatus    := TokenStatus.ERR_PAD
          regResultLen := 0
        } otherwise {
          regStatus       := TokenStatus.OK
          regResultLen    := ctLen - padVal.resize(16)
          regResultOffset := 16
        }
        regDone := True
        regIrq  := True
        goto(sIdle)
      }
    }
  }

  when(io.irqClear) {
    regIrq := False
  }

  when(io.abort) {
    regDone := True
    regIrq  := True
  }
}

/**
 * Companion object for generating Verilog.
 */
object TokenEngineVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )
  config.generateVerilog(TokenEngine()).printPruned()
  println("Successfully generated Verilog in hw/gen/TokenEngine.v")
}
