package reticulum.crypto

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/**
 * IO Bundle for the Autonomous IFAC Hashcash Stamp Grinder.
 */
case class StamperIo() extends Bundle {
  // Control & Configuration Inputs
  val start           = in Bool()
  val abort           = in Bool()
  val irqClear        = in Bool()
  val targetCost      = in UInt(8 bits)
  val midstate        = in Bits(256 bits)
  val baseCandidate   = in Bits(256 bits)
  val totalLengthBits = in UInt(64 bits)
  val startNonce      = in UInt(64 bits)
  val maxRounds       = in UInt(64 bits)

  // Status & Result Outputs
  val busy             = out Bool()
  val done             = out Bool()
  val meetsTarget      = out Bool()
  val irq              = out Bool()
  val winningCandidate = out Bits(256 bits)
  val winningDigest    = out Bits(256 bits)
  val winningZeros     = out UInt(8 bits)
  val winningNonce     = out UInt(64 bits)
  val roundsEvaluated  = out UInt(64 bits)
}

object StamperState extends SpinalEnum {
  val IDLE, GRIND, DONE = newElement()
}

/**
 * Autonomous IFAC Hashcash Stamp Grinder.
 *
 * Implements the hardware accelerator for Reticulum LXMF stamp generation (stamper.go).
 *
 * Features:
 *  - Full autonomous candidate exploration: starting from a 32-byte baseCandidate,
 *    increments a 64-bit nonce in bytes 0..7 (little-endian) matching the Go reference
 *    implementation byte for byte.
 *  - Autonomous SHA-256 block formatting: packages candidate, 0x80 padding, zeros, and
 *    total length field into 512-bit blocks on the fly.
 *  - Streaming deep pipeline: feeds candidate blocks into Sha256Pipe at 1 candidate/cycle.
 *  - Single-cycle LeadZeroCounter evaluation: evaluates candidate hashes in real time.
 *  - Nonce tag tracking with job ID: carried in lockstep through the pipeline so winning
 *    nonces are immediately captured and stale pipeline blocks from aborted jobs are ignored.
 *  - Interrupt line (irq) asserted on winning stamp or search completion.
 *  - Host cancellation (abort) and search bounding (maxRounds).
 *
 * @param roundsPerStage Pipeline unrolling factor for the internal Sha256Pipe (default 1).
 */
case class Stamper(roundsPerStage: Int = 1) extends Component {
  val io = StamperIo()

  val state = RegInit(StamperState.IDLE).simPublic()

  // Job ID to filter out residual in-flight blocks from previous searches
  val activeJobId = RegInit(U(0, 8 bits))

  // Latched configuration registers
  val cfgTargetCost      = Reg(UInt(8 bits))
  val cfgMidstate        = Reg(Bits(256 bits))
  val cfgBaseCandidate   = Reg(Bits(256 bits))
  val cfgTotalLengthBits = Reg(UInt(64 bits))
  val cfgMaxRounds       = Reg(UInt(64 bits))

  // Grinding search counters
  val nonceCounter    = Reg(UInt(64 bits))
  val dispatchedCount = Reg(UInt(64 bits))
  val evaluatedCount  = Reg(UInt(64 bits))

  // Result and status registers
  val regDone             = RegInit(False)
  val regMeetsTarget      = RegInit(False)
  val regIrq              = RegInit(False)
  val regWinningCandidate = Reg(Bits(256 bits))
  val regWinningDigest    = Reg(Bits(256 bits))
  val regWinningZeros     = Reg(UInt(8 bits))
  val regWinningNonce     = Reg(UInt(64 bits))
  val regRoundsEvaluated  = Reg(UInt(64 bits))

  // Helper: generates a 256-bit candidate by adding a 64-bit nonce offset
  // to bytes 0..7 of base in little-endian order, matching Go stamper.go
  def makeCandidate(base: Bits, nonceOffset: UInt): Bits = {
    val baseBytes = Vec(UInt(8 bits), 8)
    for (b <- 0 until 8) {
      val msb = 255 - b * 8
      val lsb = msb - 7
      baseBytes(b) := base(msb downto lsb).asUInt
    }

    val baseNonce64 = UInt(64 bits)
    for (b <- 0 until 8) {
      val dstMsb = (b + 1) * 8 - 1
      val dstLsb = b * 8
      baseNonce64(dstMsb downto dstLsb) := baseBytes(b)
    }

    val sumNonce = baseNonce64 + nonceOffset

    val cand = Bits(256 bits)
    for (b <- 0 until 8) {
      val srcMsb = (b + 1) * 8 - 1
      val srcLsb = b * 8
      val dstMsb = 255 - b * 8
      val dstLsb = dstMsb - 7
      cand(dstMsb downto dstLsb) := sumNonce(srcMsb downto srcLsb).asBits
    }
    cand(191 downto 0) := base(191 downto 0)
    cand
  }

  // Format 512-bit candidate block for the current nonce
  val currentCandidate = makeCandidate(cfgBaseCandidate, nonceCounter)

  val candidateBlock = Bits(512 bits)
  candidateBlock(511 downto 256) := currentCandidate
  candidateBlock(255 downto 248) := B"8'h80"
  candidateBlock(247 downto 64)  := B(0, 184 bits)
  candidateBlock(63 downto 0)    := cfgTotalLengthBits.asBits

  // Instantiate SHA-256 pipeline (tag: 8-bit jobId + 64-bit nonce = 72 bits)
  val pipe = Sha256Pipe(roundsPerStage = roundsPerStage, tagWidth = 72)

  val canDispatch = (cfgMaxRounds === 0) || (dispatchedCount < cfgMaxRounds)
  val isGrinding  = (state === StamperState.GRIND)

  pipe.io.cmd.valid := isGrinding && canDispatch
  pipe.io.cmd.payload.block := candidateBlock
  pipe.io.cmd.payload.useMidstate := True
  pipe.io.cmd.payload.midstate := cfgMidstate
  pipe.io.cmd.payload.tag := Cat(activeJobId, nonceCounter)

  when(pipe.io.cmd.fire) {
    dispatchedCount := dispatchedCount + 1
    nonceCounter    := nonceCounter + 1
  }

  // Always consume completed hashes from the pipeline
  pipe.io.rsp.ready := True

  // Single-cycle Leading Zero Counter on pipeline response
  val lzc = LeadZeroCounter(256)
  lzc.io.hash := pipe.io.rsp.payload.digest
  lzc.io.target := cfgTargetCost.resized

  val rspTag   = pipe.io.rsp.payload.tag
  val rspJobId = rspTag(71 downto 64).asUInt
  val rspNonce = rspTag(63 downto 0).asUInt
  val isCurrentJob = (rspJobId === activeJobId)

  // Stamper FSM
  switch(state) {
    is(StamperState.IDLE) {
      when(io.start) {
        cfgTargetCost      := io.targetCost
        cfgMidstate        := io.midstate
        cfgBaseCandidate   := io.baseCandidate
        cfgTotalLengthBits := io.totalLengthBits
        cfgMaxRounds       := io.maxRounds

        activeJobId     := activeJobId + 1
        nonceCounter    := io.startNonce
        dispatchedCount := 0
        evaluatedCount  := 0

        regDone        := False
        regMeetsTarget := False
        regIrq         := False

        state := StamperState.GRIND
      }
      when(io.irqClear) {
        regIrq := False
      }
    }

    is(StamperState.GRIND) {
      when(io.abort) {
        state              := StamperState.DONE
        regDone            := True
        regMeetsTarget     := False
        regIrq             := True
        regRoundsEvaluated := evaluatedCount
      } elsewhen(io.start) {
        // Restart search with new parameters
        cfgTargetCost      := io.targetCost
        cfgMidstate        := io.midstate
        cfgBaseCandidate   := io.baseCandidate
        cfgTotalLengthBits := io.totalLengthBits
        cfgMaxRounds       := io.maxRounds

        activeJobId     := activeJobId + 1
        nonceCounter    := io.startNonce
        dispatchedCount := 0
        evaluatedCount  := 0

        regDone        := False
        regMeetsTarget := False
        regIrq         := False
      } otherwise {
        when(pipe.io.rsp.valid && isCurrentJob) {
          evaluatedCount := evaluatedCount + 1

          when(lzc.io.meetsTarget) {
            state               := StamperState.DONE
            regDone             := True
            regMeetsTarget      := True
            regIrq              := True
            regWinningCandidate := makeCandidate(cfgBaseCandidate, rspNonce)
            regWinningDigest    := pipe.io.rsp.payload.digest
            regWinningZeros     := lzc.io.leadingZeros.resized
            regWinningNonce     := rspNonce
            regRoundsEvaluated  := evaluatedCount + 1
          } elsewhen(cfgMaxRounds > 0 && (evaluatedCount + 1 >= cfgMaxRounds)) {
            state              := StamperState.DONE
            regDone            := True
            regMeetsTarget     := False
            regIrq             := True
            regRoundsEvaluated := cfgMaxRounds
          }
        }
      }
    }

    is(StamperState.DONE) {
      when(io.start) {
        cfgTargetCost      := io.targetCost
        cfgMidstate        := io.midstate
        cfgBaseCandidate   := io.baseCandidate
        cfgTotalLengthBits := io.totalLengthBits
        cfgMaxRounds       := io.maxRounds

        activeJobId     := activeJobId + 1
        nonceCounter    := io.startNonce
        dispatchedCount := 0
        evaluatedCount  := 0

        regDone        := False
        regMeetsTarget := False
        regIrq         := False

        state := StamperState.GRIND
      }
    }
  }

  when(io.irqClear) {
    regIrq := False
  }

  // Output port connections
  io.busy             := (state === StamperState.GRIND)
  io.done             := regDone
  io.meetsTarget      := regMeetsTarget
  io.irq              := regIrq
  io.winningCandidate := regWinningCandidate
  io.winningDigest    := regWinningDigest
  io.winningZeros     := regWinningZeros
  io.winningNonce     := regWinningNonce
  io.roundsEvaluated  := regRoundsEvaluated
}

/**
 * Generates synthesis-ready Verilog for the Stamper module.
 * Run with: sbt "runMain reticulum.crypto.StamperVerilog"
 */
object StamperVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  config.generateVerilog(Stamper()).printPruned()
  println("Successfully generated Verilog in hw/gen/Stamper.v")
}
