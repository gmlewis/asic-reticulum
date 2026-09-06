package reticulum.crypto

import spinal.core._
import spinal.lib._

/**
 * Command bundle for the SHA-256 pipeline.
 *
 * @param tagWidth bit width of the arbitrary tag / nonce that travels alongside the hash
 */
case class Sha256Cmd(tagWidth: Int = 32) extends Bundle {
  val block       = Bits(512 bits)
  val useMidstate = Bool()
  val midstate    = Bits(256 bits)
  val tag         = Bits(tagWidth bits)
}

/**
 * Response bundle from the SHA-256 pipeline.
 *
 * @param tagWidth bit width of the carried tag / nonce
 */
case class Sha256Rsp(tagWidth: Int = 32) extends Bundle {
  val digest = Bits(256 bits)
  val state  = Sha256State()
  val tag    = Bits(tagWidth bits)
}

/**
 * Fully pipelined SHA-256 compression engine with midstate restore register.
 *
 * Features:
 *  - Configurable pipeline depth via roundsPerStage (default 1 round/stage = 64 stages,
 *    or 2 rounds/stage = 32 stages, 4 rounds/stage = 16 stages).
 *  - Midstate restore support: when useMidstate is True, compression starts from the
 *    provided 256-bit midstate instead of the standard FIPS 180-4 H0 vector.
 *  - Systolic sliding window message schedule: computes W_t on the fly with uniform
 *    logic across all 64 rounds without large crossbars or memories.
 *  - Tag propagation: carries an arbitrary user tag (e.g. candidate nonce) through the
 *    pipeline to associate with the resulting digest.
 *  - Full Stream handshaking (valid / ready) with backpressure stall support.
 *
 * @param roundsPerStage Number of SHA-256 compression rounds executed per clock cycle stage.
 * @param tagWidth       Width of the user metadata / nonce tag (default 32 bits).
 */
case class Sha256Pipe(roundsPerStage: Int = 1, tagWidth: Int = 32) extends Component {
  require(64 % roundsPerStage == 0, s"roundsPerStage ($roundsPerStage) must divide 64")
  val numStages: Int = 64 / roundsPerStage

  val io = new Bundle {
    val cmd = slave Stream (Sha256Cmd(tagWidth))
    val rsp = master Stream (Sha256Rsp(tagWidth))
  }

  import Sha256Functions._

  // Helper function: one round step for state and sliding window
  def stepRound(stateIn: Sha256State, windowIn: Vec[UInt], k: UInt): (Sha256State, Vec[UInt]) = {
    val wCurr = windowIn(0)
    val t1 = stateIn.h + sigma1(stateIn.e) + ch(stateIn.e, stateIn.f, stateIn.g) + k + wCurr
    val t2 = sigma0(stateIn.a) + maj(stateIn.a, stateIn.b, stateIn.c)

    val stateOut = Sha256State()
    stateOut.h := stateIn.g
    stateOut.g := stateIn.f
    stateOut.f := stateIn.e
    stateOut.e := stateIn.d + t1
    stateOut.d := stateIn.c
    stateOut.c := stateIn.b
    stateOut.b := stateIn.a
    stateOut.a := t1 + t2

    val wNew = s1(windowIn(14)) + windowIn(9) + s0(windowIn(1)) + windowIn(0)
    val windowOut = Vec(UInt(32 bits), 16)
    for (j <- 0 until 15) {
      windowOut(j) := windowIn(j + 1)
    }
    windowOut(15) := wNew

    (stateOut, windowOut)
  }

  // Pipeline control: global enable when downstream is ready or output is invalid
  val stageEnable = Bool()
  io.cmd.ready := stageEnable

  // Stage 0 inputs from io.cmd
  val stage0Valid = io.cmd.fire
  val stage0Tag   = io.cmd.tag

  val stage0HInit = Vec(UInt(32 bits), 8)
  for (i <- 0 until 8) {
    val msb = 255 - i * 32
    val lsb = msb - 31
    stage0HInit(i) := io.cmd.useMidstate ? io.cmd.midstate(msb downto lsb).asUInt | U(Sha256Constants.H0(i), 32 bits)
  }

  val stage0State = Sha256State()
  stage0State.a := stage0HInit(0)
  stage0State.b := stage0HInit(1)
  stage0State.c := stage0HInit(2)
  stage0State.d := stage0HInit(3)
  stage0State.e := stage0HInit(4)
  stage0State.f := stage0HInit(5)
  stage0State.g := stage0HInit(6)
  stage0State.h := stage0HInit(7)

  val stage0Window = Vec(UInt(32 bits), 16)
  for (i <- 0 until 16) {
    val msb = 511 - i * 32
    val lsb = msb - 31
    stage0Window(i) := io.cmd.block(msb downto lsb).asUInt
  }

  // Pipeline stage connections
  var curValid  = stage0Valid
  var curState  = stage0State
  var curWindow = stage0Window
  var curHInit  = stage0HInit
  var curTag    = stage0Tag

  val outValid  = RegInit(False)
  val outDigest = Reg(Bits(256 bits))
  val outState  = Reg(Sha256State())
  val outTag    = Reg(Bits(tagWidth bits))

  for (s <- 0 until numStages) {
    var st = curState
    var win = curWindow
    for (r <- 0 until roundsPerStage) {
      val roundIdx = s * roundsPerStage + r
      val (nextSt, nextWin) = stepRound(st, win, U(Sha256Constants.K(roundIdx), 32 bits))
      st = nextSt
      win = nextWin
    }

    if (s < numStages - 1) {
      val regValid  = RegInit(False)
      val regState  = Reg(Sha256State())
      val regWindow = Reg(Vec(UInt(32 bits), 16))
      val regHInit  = Reg(Vec(UInt(32 bits), 8))
      val regTag    = Reg(Bits(tagWidth bits))

      when(stageEnable) {
        regValid  := curValid
        regState  := st
        regWindow := win
        regHInit  := curHInit
        regTag    := curTag
      }

      curValid  = regValid
      curState  = regState
      curWindow = regWindow
      curHInit  = regHInit
      curTag    = regTag
    } else {
      // Final stage: feed-forward addition
      val finalH = Vec(UInt(32 bits), 8)
      finalH(0) := curHInit(0) + st.a
      finalH(1) := curHInit(1) + st.b
      finalH(2) := curHInit(2) + st.c
      finalH(3) := curHInit(3) + st.d
      finalH(4) := curHInit(4) + st.e
      finalH(5) := curHInit(5) + st.f
      finalH(6) := curHInit(6) + st.g
      finalH(7) := curHInit(7) + st.h

      val finalDigest = Bits(256 bits)
      for (i <- 0 until 8) {
        val msb = 255 - i * 32
        val lsb = msb - 31
        finalDigest(msb downto lsb) := finalH(i).asBits
      }

      val finalState = Sha256State()
      finalState.a := finalH(0)
      finalState.b := finalH(1)
      finalState.c := finalH(2)
      finalState.d := finalH(3)
      finalState.e := finalH(4)
      finalState.f := finalH(5)
      finalState.g := finalH(6)
      finalState.h := finalH(7)

      when(stageEnable) {
        outValid  := curValid
        outDigest := finalDigest
        outState  := finalState
        outTag    := curTag
      }
    }
  }

  stageEnable := io.rsp.ready || !outValid

  io.rsp.valid          := outValid
  io.rsp.payload.digest := outDigest
  io.rsp.payload.state  := outState
  io.rsp.payload.tag    := outTag
}

/**
 * Generates synthesis-ready Verilog for the Sha256Pipe module.
 * Run with: sbt "runMain reticulum.crypto.Sha256PipeVerilog"
 */
object Sha256PipeVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  config.generateVerilog(Sha256Pipe()).printPruned()
  println("Successfully generated Verilog in hw/gen/Sha256Pipe.v")
}
