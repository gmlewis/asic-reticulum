package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.TableDrivenPropertyChecks._
import spinal.core._
import spinal.core.sim._

class LeadZeroCounterTest extends AnyFunSuite {

  test("LeadZeroCounter: 32-bit table-driven boundary verification") {
    val testCases = Table(
      ("description", "hash", "target", "expectedZeros", "expectedMeetsTarget"),
      ("all zeros",          0x00000000L, 10, 32, true),
      ("all ones",           0xFFFFFFFFL,  1,  0, false),
      ("one leading zero",   0x7FFFFFFFL,  1,  1, true),
      ("one leading zero",   0x7FFFFFFFL,  2,  1, false),
      ("4 leading zeros",    0x0FFFFFFFL,  4,  4, true),
      ("8 leading zeros",    0x00FFFFFFL,  8,  8, true),
      ("8 leading zeros",    0x00FFFFFFL,  9,  8, false),
      ("16 leading zeros",   0x0000FFFFL, 16, 16, true),
      ("24 leading zeros",   0x000000FFL, 24, 24, true),
      ("31 leading zeros",   0x00000001L, 31, 31, true),
      ("31 leading zeros",   0x00000001L, 32, 31, false),
      ("alternating 0101",   0x55555555L,  1,  1, true),
      ("alternating 1010",   0xAAAAAAAAL,  1,  0, false)
    )

    SimConfig.compile(LeadZeroCounter(32)).doSim { dut =>
      forAll(testCases) { (desc, hash, target, expectedZeros, expectedMeetsTarget) =>
        dut.io.hash #= hash
        dut.io.target #= target
        sleep(1)

        val actualZeros = dut.io.leadingZeros.toInt
        val actualMeets = dut.io.meetsTarget.toBoolean

        assert(actualZeros == expectedZeros,
          s"Failed on $desc (hash=0x${hash.toHexString}): expected $expectedZeros, got $actualZeros")
        assert(actualMeets == expectedMeetsTarget,
          s"Failed meetsTarget on $desc (hash=0x${hash.toHexString}): expected $expectedMeetsTarget, got $actualMeets")
      }
    }
  }

  test("LeadZeroCounter: 256-bit full hash table-driven verification") {
    // BigInt helpers for 256-bit numbers
    val allOnes256 = (BigInt(1) << 256) - 1
    val oneZero256 = (BigInt(1) << 255) - 1
    val eightZeros256 = (BigInt(1) << 248) - 1
    val sixteenZeros256 = (BigInt(1) << 240) - 1
    val sixtyFourZeros256 = (BigInt(1) << 192) - 1
    val oneTwentyEightZeros256 = (BigInt(1) << 128) - 1

    val testCases = Table(
      ("description", "hash", "target", "expectedZeros", "expectedMeetsTarget"),
      ("256-bit all zeros",        BigInt(0),              32, 256, true),
      ("256-bit all zeros",        BigInt(0),             256, 256, true),
      ("256-bit all ones",         allOnes256,              1,   0, false),
      ("256-bit 1 leading zero",   oneZero256,              1,   1, true),
      ("256-bit 1 leading zero",   oneZero256,              2,   1, false),
      ("256-bit 8 leading zeros",  eightZeros256,           8,   8, true),
      ("256-bit 8 leading zeros",  eightZeros256,           9,   8, false),
      ("256-bit 16 leading zeros", sixteenZeros256,        16,  16, true),
      ("256-bit 64 leading zeros", sixtyFourZeros256,      64,  64, true),
      ("256-bit 128 leading zeros", oneTwentyEightZeros256, 128, 128, true),
      ("256-bit 255 leading zeros", BigInt(1),             255, 255, true),
      ("256-bit 255 leading zeros", BigInt(1),             256, 255, false)
    )

    SimConfig.compile(LeadZeroCounter(256)).doSim { dut =>
      forAll(testCases) { (desc, hash, target, expectedZeros, expectedMeetsTarget) =>
        dut.io.hash #= hash
        dut.io.target #= target
        sleep(1)

        val actualZeros = dut.io.leadingZeros.toInt
        val actualMeets = dut.io.meetsTarget.toBoolean

        assert(actualZeros == expectedZeros,
          s"Failed on $desc: expected $expectedZeros, got $actualZeros")
        assert(actualMeets == expectedMeetsTarget,
          s"Failed meetsTarget on $desc: expected $expectedMeetsTarget, got $actualMeets")
      }
    }
  }

  test("LeadZeroCounter: exhaustive sweep across all 256 bit positions") {
    // Verifies that for a 256-bit number with a single bit set at position i (0 <= i < 256),
    // the leading zeros count is exactly (255 - i).
    SimConfig.compile(LeadZeroCounter(256)).doSim { dut =>
      for (bitPos <- 0 until 256) {
        val singleBitHash = BigInt(1) << bitPos
        val expectedZeros = 255 - bitPos

        dut.io.hash #= singleBitHash
        dut.io.target #= expectedZeros
        sleep(1)

        val actualZeros = dut.io.leadingZeros.toInt
        val actualMeets = dut.io.meetsTarget.toBoolean

        assert(actualZeros == expectedZeros,
          s"Failed bit-position sweep at bit $bitPos: expected $expectedZeros leading zeros, got $actualZeros")
        assert(actualMeets,
          s"Expected meetsTarget=true when target == expectedZeros ($expectedZeros) at bit $bitPos")

        // Also test with target = expectedZeros + 1 (should fail)
        if (expectedZeros < 256) {
          dut.io.target #= expectedZeros + 1
          sleep(1)
          assert(!dut.io.meetsTarget.toBoolean,
            s"Expected meetsTarget=false when target == expectedZeros + 1 (${expectedZeros + 1}) at bit $bitPos")
        }
      }
    }
  }
}
