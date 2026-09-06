package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.TableDrivenPropertyChecks._
import spinal.core._
import spinal.core.sim._

class StamperTest extends AnyFunSuite {

  def hexToBigInt(hex: String): BigInt = BigInt(hex, 16)
  def bigIntToHex64(b: BigInt): String = f"$b%064x"

  // Standard test vector prefix and midstate
  val midstateHex = "ae1cd45355c7b9063b467d57d0473f5d9313bf00880190a9505c03cf569d934a"
  val baseCandidateHex = "1010101010101010101010101010101010101010101010101010101010101010"
  val totalLenBits = 768L // (64-byte prefix + 32-byte candidate) * 8

  test("Stamper: table-driven target difficulty search") {
    val testCases = Table(
      ("name", "targetCost", "expectedNonce", "expectedZeros", "expectedDigestHex"),
      (
        "Difficulty 1 (offset 0)",
        1,
        0L,
        1,
        "64f1dbc007d6610880c59ab68ea21228f6d455661e216f5676439475863c25a3"
      ),
      (
        "Difficulty 2 (offset 6)",
        2,
        6L,
        2,
        "23816ce4cb8a3b3198979512944ecba9ae59d33461b72bb6509ce1f16d5c0d6f"
      ),
      (
        "Difficulty 3 (offset 20)",
        3,
        20L,
        3,
        "1a3df6061d158f6cacabc3518534ae2d1bb529886be818d98632da7fabd1312c"
      ),
      (
        "Difficulty 5 (offset 44)",
        5,
        44L,
        5,
        "043b6129e411311c270feb070f75d2c2096673cdcf72a3b014ac13fe879701d2"
      )
    )

    SimConfig.compile(Stamper(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      forAll(testCases) { (name, targetCost, expNonce, expZeros, expDigest) =>
        // Configure and start search
        dut.io.targetCost #= targetCost
        dut.io.midstate #= hexToBigInt(midstateHex)
        dut.io.baseCandidate #= hexToBigInt(baseCandidateHex)
        dut.io.totalLengthBits #= totalLenBits
        dut.io.startNonce #= 0L
        dut.io.maxRounds #= 0L // unlimited
        dut.io.start #= true
        dut.clockDomain.waitSampling()
        dut.io.start #= false
        dut.clockDomain.waitSampling()

        // Wait until done (or timeout)
        var cycles = 1
        while (!dut.io.done.toBoolean && cycles < 300) {
          dut.clockDomain.waitSampling()
          cycles += 1
        }

        assert(dut.io.done.toBoolean, s"[$name] Did not finish in time (cycles=$cycles)")
        assert(dut.io.meetsTarget.toBoolean, s"[$name] meetsTarget should be true")
        assert(dut.io.irq.toBoolean, s"[$name] IRQ should be asserted")
        assert(!dut.io.busy.toBoolean, s"[$name] busy should be false on done")

        val gotNonce = dut.io.winningNonce.toBigInt
        val gotZeros = dut.io.winningZeros.toInt
        val gotDigest = bigIntToHex64(dut.io.winningDigest.toBigInt)
        val gotRounds = dut.io.roundsEvaluated.toBigInt

        assert(gotNonce == expNonce, f"[$name] Nonce mismatch: got $gotNonce, expected $expNonce")
        assert(gotZeros == expZeros, f"[$name] Zeros mismatch: got $gotZeros, expected $expZeros")
        assert(gotDigest == expDigest, f"[$name] Digest mismatch: got $gotDigest, expected $expDigest")
        assert(gotRounds == expNonce + 1, f"[$name] Rounds evaluated mismatch: got $gotRounds, expected ${expNonce + 1}")

        // Clear IRQ before next test
        dut.io.irqClear #= true
        dut.clockDomain.waitSampling()
        dut.io.irqClear #= false
        dut.clockDomain.waitSampling()
        assert(!dut.io.irq.toBoolean, s"[$name] IRQ should clear on irqClear")
      }
    }
  }

  test("Stamper: higher difficulty search (targetCost = 7 and 8)") {
    SimConfig.compile(Stamper(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      // Test Target 8 (expected at offset 345)
      dut.io.targetCost #= 8
      dut.io.midstate #= hexToBigInt(midstateHex)
      dut.io.baseCandidate #= hexToBigInt(baseCandidateHex)
      dut.io.totalLengthBits #= totalLenBits
      dut.io.startNonce #= 0L
      dut.io.maxRounds #= 0L
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      var cycles = 0
      while (!dut.io.done.toBoolean && cycles < 600) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      assert(dut.io.done.toBoolean, s"Target 8 did not complete in $cycles cycles")
      assert(dut.io.meetsTarget.toBoolean)
      assert(dut.io.winningNonce.toBigInt == 345L, s"Expected nonce 345, got ${dut.io.winningNonce.toBigInt}")
      assert(dut.io.winningZeros.toInt == 8)
      assert(bigIntToHex64(dut.io.winningDigest.toBigInt) == "00a28eaab217481c7a03648df84f2a1ad26b0c462fa8c03300d44bb1f6f629d4")
      assert(dut.io.roundsEvaluated.toBigInt == 346L)
    }
  }

  test("Stamper: maxRounds search bounding") {
    SimConfig.compile(Stamper(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      // Target cost 8 would win at offset 345, but limit to maxRounds = 30
      dut.io.targetCost #= 8
      dut.io.midstate #= hexToBigInt(midstateHex)
      dut.io.baseCandidate #= hexToBigInt(baseCandidateHex)
      dut.io.totalLengthBits #= totalLenBits
      dut.io.startNonce #= 0L
      dut.io.maxRounds #= 30L
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      var cycles = 0
      while (!dut.io.done.toBoolean && cycles < 200) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      assert(dut.io.done.toBoolean, "Search did not stop at maxRounds")
      assert(!dut.io.meetsTarget.toBoolean, "meetsTarget should be false when search exceeds maxRounds")
      assert(dut.io.irq.toBoolean, "IRQ should assert on maxRounds completion")
      assert(dut.io.roundsEvaluated.toBigInt == 30L, s"Expected 30 rounds, got ${dut.io.roundsEvaluated.toBigInt}")
    }
  }

  test("Stamper: host abort cancellation") {
    SimConfig.compile(Stamper(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      // Very high target (difficulty 24)
      dut.io.targetCost #= 24
      dut.io.midstate #= hexToBigInt(midstateHex)
      dut.io.baseCandidate #= hexToBigInt(baseCandidateHex)
      dut.io.totalLengthBits #= totalLenBits
      dut.io.startNonce #= 0L
      dut.io.maxRounds #= 0L
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      // Let it grind for 80 cycles
      for (_ <- 0 until 80) {
        dut.clockDomain.waitSampling()
        assert(dut.io.busy.toBoolean, "Stamper should be busy during search")
      }

      // Assert abort
      dut.io.abort #= true
      dut.clockDomain.waitSampling()
      dut.io.abort #= false

      dut.clockDomain.waitSampling()
      assert(dut.io.done.toBoolean, "Stamper did not finish after abort")
      assert(!dut.io.busy.toBoolean, "Stamper should not be busy after abort")
      assert(!dut.io.meetsTarget.toBoolean, "meetsTarget should be false on abort")
      assert(dut.io.irq.toBoolean, "IRQ should assert on abort")
    }
  }

  test("Stamper: back-to-back searches without pipeline bleed") {
    SimConfig.compile(Stamper(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      // Search 1: target 2 (winner at offset 6)
      dut.io.targetCost #= 2
      dut.io.midstate #= hexToBigInt(midstateHex)
      dut.io.baseCandidate #= hexToBigInt(baseCandidateHex)
      dut.io.totalLengthBits #= totalLenBits
      dut.io.startNonce #= 0L
      dut.io.maxRounds #= 0L
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      while (!dut.io.done.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      assert(dut.io.winningNonce.toBigInt == 6L)

      // Search 2: immediately start with target 3 (winner at offset 20)
      dut.io.targetCost #= 3
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      dut.clockDomain.waitSampling()

      while (!dut.io.done.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      assert(dut.io.winningNonce.toBigInt == 20L, s"Expected 20, got ${dut.io.winningNonce.toBigInt}")

      // Search 3: immediately start with target 5 (winner at offset 44)
      dut.io.targetCost #= 5
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      dut.clockDomain.waitSampling()

      while (!dut.io.done.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      assert(dut.io.winningNonce.toBigInt == 44L, s"Expected 44, got ${dut.io.winningNonce.toBigInt}")
    }
  }

  test("Stamper: configurable pipeline depth (roundsPerStage = 2 and 4)") {
    // Test 32-stage pipe (2 rounds per cycle)
    SimConfig.compile(Stamper(roundsPerStage = 2)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      dut.io.targetCost #= 2
      dut.io.midstate #= hexToBigInt(midstateHex)
      dut.io.baseCandidate #= hexToBigInt(baseCandidateHex)
      dut.io.totalLengthBits #= totalLenBits
      dut.io.startNonce #= 0L
      dut.io.maxRounds #= 0L
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      while (!dut.io.done.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      assert(dut.io.winningNonce.toBigInt == 6L)
      assert(bigIntToHex64(dut.io.winningDigest.toBigInt) == "23816ce4cb8a3b3198979512944ecba9ae59d33461b72bb6509ce1f16d5c0d6f")
    }

    // Test 16-stage pipe (4 rounds per cycle)
    SimConfig.compile(Stamper(roundsPerStage = 4)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.start #= false
      dut.io.abort #= false
      dut.io.irqClear #= false
      dut.clockDomain.waitSampling(5)

      dut.io.targetCost #= 3
      dut.io.midstate #= hexToBigInt(midstateHex)
      dut.io.baseCandidate #= hexToBigInt(baseCandidateHex)
      dut.io.totalLengthBits #= totalLenBits
      dut.io.startNonce #= 0L
      dut.io.maxRounds #= 0L
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      while (!dut.io.done.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      assert(dut.io.winningNonce.toBigInt == 20L)
      assert(bigIntToHex64(dut.io.winningDigest.toBigInt) == "1a3df6061d158f6cacabc3518534ae2d1bb529886be818d98632da7fabd1312c")
    }
  }
}
