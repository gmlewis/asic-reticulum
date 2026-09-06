package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.TableDrivenPropertyChecks._
import spinal.core._
import spinal.core.sim._
import scala.util.Random

class Sha256RoundTest extends AnyFunSuite {

  // Pure software reference model for SHA-256 round
  case class RefState(a: Long, b: Long, c: Long, d: Long, e: Long, f: Long, g: Long, h: Long)

  def mask32(x: Long): Long = x & 0xFFFFFFFFL
  def rotr32(x: Long, n: Int): Long = mask32((x >>> n) | (x << (32 - n)))
  def sigma0(x: Long): Long = rotr32(x, 2) ^ rotr32(x, 13) ^ rotr32(x, 22)
  def sigma1(x: Long): Long = rotr32(x, 6) ^ rotr32(x, 11) ^ rotr32(x, 25)
  def ch(e: Long, f: Long, g: Long): Long = mask32((e & f) ^ ((~e) & g))
  def maj(a: Long, b: Long, c: Long): Long = mask32((a & b) ^ (a & c) ^ (b & c))

  def refStep(s: RefState, w: Long, k: Long): RefState = {
    val t1 = mask32(s.h + sigma1(s.e) + ch(s.e, s.f, s.g) + k + w)
    val t2 = mask32(sigma0(s.a) + maj(s.a, s.b, s.c))
    RefState(
      a = mask32(t1 + t2),
      b = s.a,
      c = s.b,
      d = s.c,
      e = mask32(s.d + t1),
      f = s.e,
      g = s.f,
      h = s.g
    )
  }

  test("Sha256Round: FIPS 180-4 standard test vectors (Rounds 0 and 1)") {
    // Standard initial SHA-256 state H^(0)
    val h0 = RefState(
      a = 0x6a09e667L, b = 0xbb67ae85L, c = 0x3c6ef372L, d = 0xa54ff53aL,
      e = 0x510e527fL, f = 0x9b05688cL, g = 0x1f83d9abL, h = 0x5be0cd19L
    )

    // Expected state after round 0 on "abc" (W0 = 0x61626380, K0 = 0x428a2f98)
    val expectedR0 = RefState(
      a = 0x5d6aebcdL, b = 0x6a09e667L, c = 0xbb67ae85L, d = 0x3c6ef372L,
      e = 0xfa2a4622L, f = 0x510e527fL, g = 0x9b05688cL, h = 0x1f83d9abL
    )

    // Expected state after round 1 on "abc" (W1 = 0x00000000, K1 = 0x71374491)
    val expectedR1 = RefState(
      a = 0x5a6ad9adL, b = 0x5d6aebcdL, c = 0x6a09e667L, d = 0xbb67ae85L,
      e = 0x78ce7989L, f = 0xfa2a4622L, g = 0x510e527fL, h = 0x9b05688cL
    )

    val standardRounds = Table(
      ("roundName", "stateIn", "w", "k", "expectedState"),
      ("Round 0 (abc)", h0,         0x61626380L, 0x428a2f98L, expectedR0),
      ("Round 1 (abc)", expectedR0, 0x00000000L, 0x71374491L, expectedR1)
    )

    SimConfig.compile(Sha256Round()).doSim { dut =>
      forAll(standardRounds) { (name, sIn, w, k, exp) =>
        dut.io.stateIn.a #= sIn.a
        dut.io.stateIn.b #= sIn.b
        dut.io.stateIn.c #= sIn.c
        dut.io.stateIn.d #= sIn.d
        dut.io.stateIn.e #= sIn.e
        dut.io.stateIn.f #= sIn.f
        dut.io.stateIn.g #= sIn.g
        dut.io.stateIn.h #= sIn.h
        dut.io.w #= w
        dut.io.k #= k
        sleep(1)

        val outA = dut.io.stateOut.a.toLong
        val outB = dut.io.stateOut.b.toLong
        val outC = dut.io.stateOut.c.toLong
        val outD = dut.io.stateOut.d.toLong
        val outE = dut.io.stateOut.e.toLong
        val outF = dut.io.stateOut.f.toLong
        val outG = dut.io.stateOut.g.toLong
        val outH = dut.io.stateOut.h.toLong

        assert(outA == exp.a, f"[$name] a mismatch: expected 0x${exp.a}%08x, got 0x$outA%08x")
        assert(outB == exp.b, f"[$name] b mismatch: expected 0x${exp.b}%08x, got 0x$outB%08x")
        assert(outC == exp.c, f"[$name] c mismatch: expected 0x${exp.c}%08x, got 0x$outC%08x")
        assert(outD == exp.d, f"[$name] d mismatch: expected 0x${exp.d}%08x, got 0x$outD%08x")
        assert(outE == exp.e, f"[$name] e mismatch: expected 0x${exp.e}%08x, got 0x$outE%08x")
        assert(outF == exp.f, f"[$name] f mismatch: expected 0x${exp.f}%08x, got 0x$outF%08x")
        assert(outG == exp.g, f"[$name] g mismatch: expected 0x${exp.g}%08x, got 0x$outG%08x")
        assert(outH == exp.h, f"[$name] h mismatch: expected 0x${exp.h}%08x, got 0x$outH%08x")
      }
    }
  }

  test("Sha256Round: boundary corner cases (all zeros and all ones)") {
    val zeroState = RefState(0, 0, 0, 0, 0, 0, 0, 0)
    val onesState = RefState(
      0xFFFFFFFFL, 0xFFFFFFFFL, 0xFFFFFFFFL, 0xFFFFFFFFL,
      0xFFFFFFFFL, 0xFFFFFFFFL, 0xFFFFFFFFL, 0xFFFFFFFFL
    )

    val cornerCases = Table(
      ("caseName", "stateIn", "w", "k"),
      ("all zeros", zeroState, 0L, 0L),
      ("all ones",  onesState, 0xFFFFFFFFL, 0xFFFFFFFFL),
      ("mixed zeroes and ones", zeroState, 0xFFFFFFFFL, 0xFFFFFFFFL),
      ("ones state with zero input", onesState, 0L, 0L)
    )

    SimConfig.compile(Sha256Round()).doSim { dut =>
      forAll(cornerCases) { (name, sIn, w, k) =>
        val exp = refStep(sIn, w, k)

        dut.io.stateIn.a #= sIn.a
        dut.io.stateIn.b #= sIn.b
        dut.io.stateIn.c #= sIn.c
        dut.io.stateIn.d #= sIn.d
        dut.io.stateIn.e #= sIn.e
        dut.io.stateIn.f #= sIn.f
        dut.io.stateIn.g #= sIn.g
        dut.io.stateIn.h #= sIn.h
        dut.io.w #= w
        dut.io.k #= k
        sleep(1)

        assert(dut.io.stateOut.a.toLong == exp.a, s"[$name] a mismatch")
        assert(dut.io.stateOut.b.toLong == exp.b, s"[$name] b mismatch")
        assert(dut.io.stateOut.c.toLong == exp.c, s"[$name] c mismatch")
        assert(dut.io.stateOut.d.toLong == exp.d, s"[$name] d mismatch")
        assert(dut.io.stateOut.e.toLong == exp.e, s"[$name] e mismatch")
        assert(dut.io.stateOut.f.toLong == exp.f, s"[$name] f mismatch")
        assert(dut.io.stateOut.g.toLong == exp.g, s"[$name] g mismatch")
        assert(dut.io.stateOut.h.toLong == exp.h, s"[$name] h mismatch")
      }
    }
  }

  test("Sha256Round: 100 randomized stress cycles vs software reference model") {
    val rng = new Random(42) // Deterministic seed for reproducible tests

    SimConfig.compile(Sha256Round()).doSim { dut =>
      var currState = RefState(
        a = 0x6a09e667L, b = 0xbb67ae85L, c = 0x3c6ef372L, d = 0xa54ff53aL,
        e = 0x510e527fL, f = 0x9b05688cL, g = 0x1f83d9abL, h = 0x5be0cd19L
      )

      for (cycle <- 0 until 100) {
        val w = mask32(rng.nextLong())
        val k = mask32(rng.nextLong())
        val exp = refStep(currState, w, k)

        dut.io.stateIn.a #= currState.a
        dut.io.stateIn.b #= currState.b
        dut.io.stateIn.c #= currState.c
        dut.io.stateIn.d #= currState.d
        dut.io.stateIn.e #= currState.e
        dut.io.stateIn.f #= currState.f
        dut.io.stateIn.g #= currState.g
        dut.io.stateIn.h #= currState.h
        dut.io.w #= w
        dut.io.k #= k
        sleep(1)

        val outA = dut.io.stateOut.a.toLong
        val outB = dut.io.stateOut.b.toLong
        val outC = dut.io.stateOut.c.toLong
        val outD = dut.io.stateOut.d.toLong
        val outE = dut.io.stateOut.e.toLong
        val outF = dut.io.stateOut.f.toLong
        val outG = dut.io.stateOut.g.toLong
        val outH = dut.io.stateOut.h.toLong

        assert(outA == exp.a && outB == exp.b && outC == exp.c && outD == exp.d &&
               outE == exp.e && outF == exp.f && outG == exp.g && outH == exp.h,
          f"Random stress cycle $cycle failed: got (0x$outA%08x, 0x$outE%08x) vs exp (0x${exp.a}%08x, 0x${exp.e}%08x)")

        // Cascade state for the next cycle
        currState = exp
      }
    }
  }
}
