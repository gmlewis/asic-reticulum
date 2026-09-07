package reticulum.parity

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.TableDrivenPropertyChecks._
import reticulum.bus.{QspiOpcode, QspiTop}
import reticulum.crypto.{Sha256Pipe, Stamper, X25519Ladder, TokenEngine}
import spinal.core._
import spinal.core.sim._

class GoReticulumParityTest extends AnyFunSuite {

  def bigIntToHex64(b: BigInt): String = f"$b%064x"
  def hexToBigInt(hex: String): BigInt = if (hex.isEmpty) BigInt(0) else BigInt(hex, 16)
  def hexToBytes(hex: String): Seq[Int] = {
    if (hex.isEmpty) Seq()
    else hex.grouped(2).map(Integer.parseInt(_, 16)).toSeq
  }
  def bytesToHex(bytes: Seq[Int]): String = {
    bytes.map(b => f"$b%02x").mkString
  }

  // Helper for QSPI writes
  def qspiWriteByte(dut: QspiTop, byteVal: Int): Unit = {
    val highNibble = (byteVal >> 4) & 0x0F
    val lowNibble  = byteVal & 0x0F

    dut.io.data_in #= highNibble
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false

    dut.io.data_in #= lowNibble
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false
  }

  // Helper for QSPI reads
  def qspiReadByte(dut: QspiTop): Int = {
    dut.clockDomain.waitSampling(4)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    val highNibble = dut.io.data_out.toInt
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false

    dut.clockDomain.waitSampling(4)
    dut.io.sclk #= true
    dut.clockDomain.waitSampling(2)
    val lowNibble = dut.io.data_out.toInt
    dut.clockDomain.waitSampling(2)
    dut.io.sclk #= false

    (highNibble << 4) | lowNibble
  }

  def qspiSendCommand(dut: QspiTop, opcode: Int, payload: Seq[Int] = Seq()): Unit = {
    val lenMsb = (payload.length >> 8) & 0xFF
    val lenLsb = payload.length & 0xFF

    dut.io.cs_n #= false
    dut.clockDomain.waitSampling(4)

    qspiWriteByte(dut, opcode)
    qspiWriteByte(dut, lenMsb)
    qspiWriteByte(dut, lenLsb)
    for (b <- payload) {
      qspiWriteByte(dut, b)
    }

    dut.clockDomain.waitSampling(4)
    dut.io.cs_n #= true
    dut.clockDomain.waitSampling(4)
  }

  test("Stamper: table-driven parity against go-reticulum golden vectors") {
    val tableCases = Table(
      "case",
      GoldenVectors.cases: _*
    )

    SimConfig.compile(Stamper(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      forAll(tableCases) { c =>
        dut.io.targetCost #= c.targetCost
        dut.io.midstate #= hexToBigInt(c.midstateHex)
        dut.io.baseCandidate #= hexToBigInt(c.baseCandidateHex)
        dut.io.totalLengthBits #= c.totalLenBits
        dut.io.startNonce #= c.startNonce
        dut.io.maxRounds #= 0L

        dut.io.start #= true
        dut.clockDomain.waitSampling()
        dut.io.start #= false

        var timeout = 0
        val maxCycles = (c.expectedRounds + 120).toInt
        while (!dut.io.irq.toBoolean && timeout < maxCycles) {
          dut.clockDomain.waitSampling()
          timeout += 1
        }

        assert(dut.io.irq.toBoolean, s"${c.name}: IRQ not asserted within $maxCycles cycles")
        assert(dut.io.done.toBoolean, s"${c.name}: done flag expected true")
        assert(dut.io.meetsTarget.toBoolean, s"${c.name}: meetsTarget expected true")
        assert(
          dut.io.winningNonce.toBigInt.toLong == c.expectedNonce,
          s"${c.name}: Nonce mismatch: got ${dut.io.winningNonce.toBigInt.toLong}, want ${c.expectedNonce}"
        )
        assert(
          dut.io.winningZeros.toInt == c.expectedZeros,
          s"${c.name}: Zeros mismatch: got ${dut.io.winningZeros.toInt}, want ${c.expectedZeros}"
        )
        assert(
          dut.io.roundsEvaluated.toBigInt.toLong == c.expectedRounds,
          s"${c.name}: Rounds mismatch: got ${dut.io.roundsEvaluated.toBigInt.toLong}, want ${c.expectedRounds}"
        )

        val gotDigestHex = bigIntToHex64(dut.io.winningDigest.toBigInt)
        assert(
          gotDigestHex == c.expectedDigestHex,
          s"${c.name}: Digest mismatch:\n  got:  $gotDigestHex\n  want: ${c.expectedDigestHex}"
        )

        val gotCandHex = bigIntToHex64(dut.io.winningCandidate.toBigInt)
        assert(
          gotCandHex == c.expectedCandidateHex,
          s"${c.name}: Candidate mismatch:\n  got:  $gotCandHex\n  want: ${c.expectedCandidateHex}"
        )

        // Clear IRQ for next iteration
        dut.io.irqClear #= true
        dut.clockDomain.waitSampling()
        dut.io.irqClear #= false
        dut.clockDomain.waitSampling(5)
      }
    }
  }

  test("QspiTop: end-to-end QSPI streaming parity against go-reticulum golden vectors") {
    // Representative subset of golden cases across LXMF, Peering, and Offset search
    val testCases = Seq(
      GoldenVectors.cases.find(_.name == "LXMF Message - Target 2").get,
      GoldenVectors.cases.find(_.name == "RNode Peering - Target 4").get,
      GoldenVectors.cases.find(_.name == "Offset Search - Target 5").get
    )

    SimConfig.compile(QspiTop(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.sclk #= false
      dut.io.cs_n #= true
      dut.io.data_in #= 0
      dut.clockDomain.waitSampling(5)

      for (c <- testCases) {
        val midBytes   = hexToBytes(c.midstateHex)
        val candBytes  = hexToBytes(c.baseCandidateHex)
        val lenBytes   = (0 until 8).map(i => ((c.totalLenBits >> (56 - i * 8)) & 0xFF).toInt)
        val nonceBytes = (0 until 8).map(i => ((c.startNonce >> (56 - i * 8)) & 0xFF).toInt)
        val maxRBytes  = Seq(0, 0, 0, 0, 0, 0, 0, 0)

        val payload = Seq(c.targetCost) ++ midBytes ++ candBytes ++ lenBytes ++ nonceBytes ++ maxRBytes
        assert(payload.length == 89)

        // 1. Dispatch search over QSPI
        qspiSendCommand(dut, QspiOpcode.OP_STAMP_GRIND, payload)

        // 2. Await hardware interrupt
        var waitCycles = 0
        val maxCycles = (c.expectedRounds * 8 + 300).toInt
        while (dut.io.irq_n.toBoolean && waitCycles < maxCycles) {
          dut.clockDomain.waitSampling()
          waitCycles += 1
        }
        assert(!dut.io.irq_n.toBoolean, s"${c.name}: irq_n did not assert low")

        // 3. Read back 82 result bytes over QSPI
        dut.io.cs_n #= false
        dut.clockDomain.waitSampling(4)

        qspiWriteByte(dut, QspiOpcode.OP_STAMP_READ)
        qspiWriteByte(dut, 0x00)
        qspiWriteByte(dut, 0x00)
        dut.clockDomain.waitSampling(8)

        val readBytes = collection.mutable.ArrayBuffer[Int]()
        for (_ <- 0 until 82) {
          readBytes += qspiReadByte(dut)
        }

        dut.clockDomain.waitSampling(4)
        dut.io.cs_n #= true
        dut.clockDomain.waitSampling(5)

        // Byte 0: status flag (0x0B)
        assert(readBytes(0) == 0x0B, f"${c.name}: status mismatch: 0x${readBytes(0)}%02x")
        // Byte 1: zeros
        assert(readBytes(1) == c.expectedZeros, s"${c.name}: zeros mismatch")
        // Bytes 2..9: nonce
        val gotNonce = readBytes.slice(2, 10).foldLeft(0L)((acc, b) => (acc << 8) | b)
        assert(gotNonce == c.expectedNonce, s"${c.name}: nonce mismatch")
        // Bytes 10..17: rounds
        val gotRounds = readBytes.slice(10, 18).foldLeft(0L)((acc, b) => (acc << 8) | b)
        assert(gotRounds == c.expectedRounds, s"${c.name}: rounds mismatch")
        // Bytes 18..49: digest
        val gotDigest = bytesToHex(readBytes.slice(18, 50).toSeq)
        assert(gotDigest == c.expectedDigestHex, s"${c.name}: digest mismatch")
        // Bytes 50..81: candidate
        val gotCand = bytesToHex(readBytes.slice(50, 82).toSeq)
        assert(gotCand == c.expectedCandidateHex, s"${c.name}: candidate mismatch")

        // 4. Clear IRQ
        qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
        dut.clockDomain.waitSampling(5)
        assert(dut.io.irq_n.toBoolean, s"${c.name}: irq_n did not deassert")
      }
    }
  }

  test("Stamper: sustained 1 candidate/cycle search throughput") {
    // Run high difficulty search (Target 20) and measure clock cycles vs roundsEvaluated
    val c = GoldenVectors.cases.head

    SimConfig.compile(Stamper(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      dut.io.targetCost #= 20 // Unreachable in short test
      dut.io.midstate #= hexToBigInt(c.midstateHex)
      dut.io.baseCandidate #= hexToBigInt(c.baseCandidateHex)
      dut.io.totalLengthBits #= c.totalLenBits
      dut.io.startNonce #= 0L
      dut.io.maxRounds #= 0L

      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      // Grinding duration: 64 cycles pipeline latency + 60 evaluation cycles = 124 cycles
      val evalCycles = 60
      dut.clockDomain.waitSampling(64 + evalCycles)

      // Halt search to latch evaluatedCount into roundsEvaluated
      dut.io.abort #= true
      dut.clockDomain.waitSampling()
      dut.io.abort #= false
      dut.clockDomain.waitSampling(2)

      val evaluated = dut.io.roundsEvaluated.toBigInt.toLong
      assert(
        evaluated >= evalCycles,
        s"Expected >= $evalCycles candidates evaluated, got $evaluated"
      )
      val throughput = evaluated.toDouble / evalCycles
      assert(
        throughput >= 1.0,
        s"Expected throughput >= 1.0 candidate/cycle, got $throughput"
      )
    }
  }

  test("Sha256Pipe: multi-block raw message streaming parity") {
    def padSha256(msgBytes: Seq[Int]): Seq[BigInt] = {
      val bitLen = msgBytes.length.toLong * 8L
      val withOne = msgBytes :+ 0x80
      val padZeroCount = ((56 - (withOne.length % 64)) % 64 + 64) % 64
      val padded = withOne ++ Seq.fill(padZeroCount)(0)
      val lenBytes = (0 until 8).map(i => ((bitLen >> (56 - i * 8)) & 0xFF).toInt)
      val allBytes = padded ++ lenBytes
      allBytes.grouped(64).map { chunk =>
        chunk.foldLeft(BigInt(0))((acc, b) => (acc << 8) | b)
      }.toSeq
    }

    SimConfig.compile(Sha256Pipe(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.cmd.payload.block #= 0
      dut.io.cmd.payload.useMidstate #= false
      dut.io.cmd.payload.midstate #= 0
      dut.io.cmd.payload.tag #= 0
      dut.io.rsp.ready #= true
      dut.clockDomain.waitSampling(5)

      for (c <- GoldenVectors.sha256Cases) {
        val msgBytes = hexToBytes(c.messageHex)
        val blocks = padSha256(msgBytes)

        var runningDigest = BigInt(0)
        for ((blk, idx) <- blocks.zipWithIndex) {
          dut.io.cmd.valid #= true
          dut.io.cmd.payload.block #= blk
          dut.io.cmd.payload.useMidstate #= (idx > 0)
          dut.io.cmd.payload.midstate #= runningDigest
          dut.io.cmd.payload.tag #= idx
          dut.clockDomain.waitSampling()
          dut.io.cmd.valid #= false

          // Await pipeline response (64 cycles)
          var cycles = 0
          while (!dut.io.rsp.valid.toBoolean && cycles < 100) {
            dut.clockDomain.waitSampling()
            cycles += 1
          }
          assert(dut.io.rsp.valid.toBoolean, s"${c.name}: Response timed out on block $idx")
          runningDigest = dut.io.rsp.payload.digest.toBigInt
          dut.clockDomain.waitSampling()
        }

        val gotDigestHex = bigIntToHex64(runningDigest)
        assert(
          gotDigestHex == c.expectedDigestHex,
          s"${c.name}: Hash mismatch:\n  got:  $gotDigestHex\n  want: ${c.expectedDigestHex}"
        )
      }
    }
  }

  def hexToBigIntLE(hex: String): BigInt = {
    val bytes = hex.sliding(2, 2).toArray.map(s => Integer.parseInt(s, 16).toByte)
    var bi = BigInt(0)
    for (i <- 0 until bytes.length) {
      bi |= (BigInt(bytes(i) & 0xFF) << (i * 8))
    }
    bi
  }

  def bigIntToHexLE(bi: BigInt): String = {
    val sb = new StringBuilder
    for (i <- 0 until 32) {
      val b = (bi >> (i * 8)) & 0xFF
      sb.append(f"$b%02x")
    }
    sb.toString()
  }

  test("X25519: table-driven parity against go-reticulum golden vectors") {
    val x25519Table = Table(
      "case",
      GoldenVectors.x25519Cases: _*
    )

    SimConfig.compile(X25519Ladder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      forAll(x25519Table) { c =>
        dut.io.scalar #= hexToBigIntLE(c.scalarHex)
        dut.io.uCoord #= hexToBigIntLE(c.uCoordHex)
        dut.io.start #= true
        dut.clockDomain.waitSampling()
        dut.io.start #= false
        dut.clockDomain.waitSampling()

        dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)
        val gotSharedHex = bigIntToHexLE(dut.io.result.toBigInt)
        assert(
          gotSharedHex == c.expectedSharedHex,
          s"${c.name}: Shared secret mismatch:\n  got:  $gotSharedHex\n  want: ${c.expectedSharedHex}"
        )
      }
    }
  }

  test("QspiTop: end-to-end X25519 QSPI streaming parity against go-reticulum golden vectors") {
    val x25519Table = Table(
      "case",
      GoldenVectors.x25519Cases: _*
    )

    SimConfig.compile(QspiTop(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.sclk #= false
      dut.io.cs_n #= true
      dut.io.data_in #= 0
      dut.clockDomain.waitSampling(5)

      forAll(x25519Table) { c =>
        val scalarBytes = hexToBytes(c.scalarHex)
        val uBytes      = hexToBytes(c.uCoordHex)
        val payload     = scalarBytes ++ uBytes
        assert(payload.length == 64)

        // 1. Dispatch OP_X25519_MULT
        qspiSendCommand(dut, QspiOpcode.OP_X25519_MULT, payload)

        // 2. Await hardware IRQ (active low)
        var waitCycles = 0
        val maxWait    = 6000
        while (dut.io.irq_n.toBoolean && waitCycles < maxWait) {
          dut.clockDomain.waitSampling(10)
          waitCycles += 10
        }
        assert(!dut.io.irq_n.toBoolean, s"${c.name}: IRQ was not asserted low within $maxWait cycles")

        // 3. Read back 32-byte shared secret via OP_X25519_READ
        dut.io.cs_n #= false
        dut.clockDomain.waitSampling(4)

        qspiWriteByte(dut, QspiOpcode.OP_X25519_READ)
        qspiWriteByte(dut, 0x00)
        qspiWriteByte(dut, 0x00)
        dut.clockDomain.waitSampling(8)

        val resultBytes = collection.mutable.ArrayBuffer[Int]()
        for (_ <- 0 until 32) {
          resultBytes += qspiReadByte(dut)
        }

        dut.clockDomain.waitSampling(4)
        dut.io.cs_n #= true
        dut.clockDomain.waitSampling(5)

        val gotSharedHex = bytesToHex(resultBytes.toSeq)
        assert(
          gotSharedHex == c.expectedSharedHex,
          s"${c.name}: QSPI shared secret mismatch:\n  got:  $gotSharedHex\n  want: ${c.expectedSharedHex}"
        )

        // 4. Clear interrupt
        qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
        dut.clockDomain.waitSampling(5)
        assert(dut.io.irq_n.toBoolean, s"${c.name}: irq_n should return high after OP_IRQ_CLEAR")
      }
    }
  }

  test("TokenEngine: table-driven Seal and Open parity against go-reticulum golden vectors") {
    val tokenTable = Table(
      "case",
      GoldenVectors.tokenCases: _*
    )

    SimConfig.compile(TokenEngine()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
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

      forAll(tokenTable) { c =>
        val keyBytes   = hexToBytes(c.keyHex)
        val signKeyHex = c.keyHex.substring(0, 32)
        val encKeyHex  = c.keyHex.substring(32, 64)
        val ivHex      = c.ivHex
        val ptBytes    = hexToBytes(c.plaintextHex)

        // 1. Write Plaintext to mem[16 .. 16 + len - 1]
        if (ptBytes.nonEmpty) {
          dut.io.hostWrEn #= true
          for (i <- ptBytes.indices) {
            dut.io.hostWrAddr #= 16 + i
            dut.io.hostWrData #= ptBytes(i)
            dut.clockDomain.waitSampling()
          }
          dut.io.hostWrEn #= false
          dut.clockDomain.waitSampling()
        }

        // 2. Start Seal
        dut.io.signKey #= BigInt(signKeyHex, 16)
        dut.io.encKey  #= BigInt(encKeyHex, 16)
        dut.io.iv      #= BigInt(ivHex, 16)
        dut.io.dataLen #= ptBytes.length
        dut.io.mode    #= true
        dut.io.start   #= true
        dut.clockDomain.waitSampling()
        dut.io.start   #= false
        dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
        dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)

        assert(dut.io.status.toBigInt == 0, s"${c.name}: Seal status should be OK (0)")
        val sealedLen = dut.io.resultLen.toInt

        // 3. Read sealed token from mem[0 .. sealedLen - 1]
        val sealedToken = collection.mutable.ArrayBuffer[Int]()
        for (i <- 0 until sealedLen) {
          dut.io.hostRdAddr #= i
          dut.clockDomain.waitSampling()
          sealedToken += dut.io.hostRdData.toInt
        }

        val sealedTokenHex = bytesToHex(sealedToken.toSeq)
        assert(
          sealedTokenHex == c.expectedTokenHex,
          s"${c.name}: Sealed token mismatch:\n got:  $sealedTokenHex\n want: ${c.expectedTokenHex}"
        )

        // Clear IRQ
        dut.io.irqClear #= true
        dut.clockDomain.waitSampling()
        dut.io.irqClear #= false
        dut.clockDomain.waitSampling()

        // 4. Open the sealed token
        dut.io.dataLen #= sealedLen
        dut.io.mode    #= false
        dut.io.start   #= true
        dut.clockDomain.waitSampling()
        dut.io.start   #= false
        dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
        dut.clockDomain.waitSamplingWhere(dut.io.done.toBoolean)

        assert(dut.io.status.toBigInt == 0, s"${c.name}: Open status should be OK (0)")
        val openedLen = dut.io.resultLen.toInt
        assert(openedLen == ptBytes.length, s"${c.name}: Opened length mismatch: exp ${ptBytes.length}, got $openedLen")

        // 5. Read decrypted plaintext from mem[16 .. 16 + openedLen - 1]
        val recoveredBytes = collection.mutable.ArrayBuffer[Int]()
        for (i <- 0 until openedLen) {
          dut.io.hostRdAddr #= 16 + i
          dut.clockDomain.waitSampling()
          recoveredBytes += dut.io.hostRdData.toInt
        }

        val recoveredHex = bytesToHex(recoveredBytes.toSeq)
        assert(
          recoveredHex == c.plaintextHex,
          s"${c.name}: Plaintext mismatch:\n got:  $recoveredHex\n want: ${c.plaintextHex}"
        )

        // Clear IRQ
        dut.io.irqClear #= true
        dut.clockDomain.waitSampling()
        dut.io.irqClear #= false
        dut.clockDomain.waitSampling()
      }
    }
  }

  test("QspiTop: table-driven Token Seal and Open parity over QSPI") {
    val tokenTable = Table(
      "case",
      GoldenVectors.tokenCases: _*
    )

    SimConfig.compile(QspiTop(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.sclk #= false
      dut.io.cs_n #= true
      dut.io.data_in #= 0
      dut.clockDomain.waitSampling(5)

      forAll(tokenTable) { c =>
        val keyBytes     = hexToBytes(c.keyHex)
        val signKeyBytes = keyBytes.take(16)
        val encKeyBytes  = keyBytes.drop(16).take(16)
        val ivBytes      = hexToBytes(c.ivHex)
        val ptBytes      = hexToBytes(c.plaintextHex)

        // 1. Dispatch OP_TOKEN_SEAL
        val sealPayload = signKeyBytes ++ encKeyBytes ++ ivBytes ++ ptBytes
        qspiSendCommand(dut, QspiOpcode.OP_TOKEN_SEAL, sealPayload)

        // 2. Await hardware IRQ (active low)
        var waitCycles = 0
        val maxWait    = 3000
        while (dut.io.irq_n.toBoolean && waitCycles < maxWait) {
          dut.clockDomain.waitSampling(10)
          waitCycles += 10
        }
        assert(!dut.io.irq_n.toBoolean, s"${c.name}: IRQ was not asserted low for Seal")

        // 3. Read back sealed token via OP_TOKEN_READ
        dut.io.cs_n #= false
        dut.clockDomain.waitSampling(4)

        qspiWriteByte(dut, QspiOpcode.OP_TOKEN_READ)
        qspiWriteByte(dut, 0x00)
        qspiWriteByte(dut, 0x00)
        dut.clockDomain.waitSampling(8)

        val sealStatus = qspiReadByte(dut)
        val sealLenMsb = qspiReadByte(dut)
        val sealLenLsb = qspiReadByte(dut)
        val sealedLen  = (sealLenMsb << 8) | sealLenLsb

        assert(sealStatus == 0, s"${c.name}: Expected OK status (0), got $sealStatus")

        val sealedToken = collection.mutable.ArrayBuffer[Int]()
        for (_ <- 0 until sealedLen) {
          sealedToken += qspiReadByte(dut)
        }

        dut.clockDomain.waitSampling(4)
        dut.io.cs_n #= true
        dut.clockDomain.waitSampling(5)

        val sealedHex = bytesToHex(sealedToken.toSeq)
        assert(
          sealedHex == c.expectedTokenHex,
          s"${c.name}: QSPI Sealed token mismatch:\n got:  $sealedHex\n want: ${c.expectedTokenHex}"
        )

        // 4. Clear interrupt
        qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
        dut.clockDomain.waitSampling(5)
        assert(dut.io.irq_n.toBoolean, s"${c.name}: irq_n should return high after OP_IRQ_CLEAR")

        // 5. Dispatch OP_TOKEN_OPEN
        val openPayload = signKeyBytes ++ encKeyBytes ++ sealedToken.toSeq
        qspiSendCommand(dut, QspiOpcode.OP_TOKEN_OPEN, openPayload)

        // 6. Await hardware IRQ
        waitCycles = 0
        while (dut.io.irq_n.toBoolean && waitCycles < maxWait) {
          dut.clockDomain.waitSampling(10)
          waitCycles += 10
        }
        assert(!dut.io.irq_n.toBoolean, s"${c.name}: IRQ was not asserted low for Open")

        // 7. Read back plaintext via OP_TOKEN_READ
        dut.io.cs_n #= false
        dut.clockDomain.waitSampling(4)

        qspiWriteByte(dut, QspiOpcode.OP_TOKEN_READ)
        qspiWriteByte(dut, 0x00)
        qspiWriteByte(dut, 0x00)
        dut.clockDomain.waitSampling(8)

        val openStatus = qspiReadByte(dut)
        val openLenMsb = qspiReadByte(dut)
        val openLenLsb = qspiReadByte(dut)
        val openedLen  = (openLenMsb << 8) | openLenLsb

        assert(openStatus == 0, s"${c.name}: Expected OK status (0), got $openStatus")
        assert(openedLen == ptBytes.length, s"${c.name}: Expected opened len ${ptBytes.length}, got $openedLen")

        val recoveredBytes = collection.mutable.ArrayBuffer[Int]()
        for (_ <- 0 until openedLen) {
          recoveredBytes += qspiReadByte(dut)
        }

        dut.clockDomain.waitSampling(4)
        dut.io.cs_n #= true
        dut.clockDomain.waitSampling(5)

        val recoveredHex = bytesToHex(recoveredBytes.toSeq)
        assert(
          recoveredHex == c.plaintextHex,
          s"${c.name}: QSPI Plaintext mismatch:\n got:  $recoveredHex\n want: ${c.plaintextHex}"
        )

        // 8. Clear interrupt
        qspiSendCommand(dut, QspiOpcode.OP_IRQ_CLEAR)
        dut.clockDomain.waitSampling(5)
        assert(dut.io.irq_n.toBoolean, s"${c.name}: irq_n should return high after OP_IRQ_CLEAR")
      }
    }
  }
}

