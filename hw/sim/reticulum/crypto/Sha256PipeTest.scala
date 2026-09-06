package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.TableDrivenPropertyChecks._
import spinal.core._
import spinal.core.sim._

class Sha256PipeTest extends AnyFunSuite {

  def hexToBigInt(hex: String): BigInt = BigInt(hex, 16)
  def bigIntToHex64(b: BigInt): String = f"$b%064x"

  test("Sha256Pipe: standard FIPS 180-4 single-block vectors") {
    // Standard test vectors (block padded to 64 bytes = 512 bits)
    val testCases = Table(
      ("name", "blockHex", "expectedHash", "tag"),
      (
        "abc",
        "61626380000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000018",
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        0x101L
      ),
      (
        "empty string",
        "80000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        0x202L
      ),
      (
        "quick brown fox",
        "54686520717569636b2062726f776e20666f78206a756d7073206f76657220746865206c617a7920646f67800000000000000000000000000000000000000158",
        "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
        0x303L
      )
    )

    SimConfig.compile(Sha256Pipe(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.cmd.payload.block #= 0
      dut.io.cmd.payload.useMidstate #= false
      dut.io.cmd.payload.midstate #= 0
      dut.io.cmd.payload.tag #= 0
      dut.io.rsp.ready #= true
      dut.clockDomain.waitSampling()

      forAll(testCases) { (name, blockHex, expectedHash, tag) =>
        // Send single block
        dut.io.cmd.valid #= true
        dut.io.cmd.payload.block #= hexToBigInt(blockHex)
        dut.io.cmd.payload.useMidstate #= false
        dut.io.cmd.payload.midstate #= 0
        dut.io.cmd.payload.tag #= tag
        dut.clockDomain.waitSampling()

        dut.io.cmd.valid #= false

        // Wait until output valid (pipeline latency = 64 cycles)
        var cycles = 0
        while (!dut.io.rsp.valid.toBoolean && cycles < 100) {
          dut.clockDomain.waitSampling()
          cycles += 1
        }

        assert(dut.io.rsp.valid.toBoolean, s"[$name] Response never arrived within timeout")
        val gotHash = bigIntToHex64(dut.io.rsp.payload.digest.toBigInt)
        val gotTag = dut.io.rsp.payload.tag.toLong

        assert(gotHash == expectedHash, f"[$name] Hash mismatch: got $gotHash, expected $expectedHash")
        assert(gotTag == tag, f"[$name] Tag mismatch: got 0x$gotTag%x, expected 0x$tag%x")

        dut.clockDomain.waitSampling()
      }
    }
  }

  test("Sha256Pipe: 2-block midstate restore verification (Reticulum LXMF Stamp pattern)") {
    // Prefix (64 bytes = 512 bits)
    val prefixBlockHex = "5265746963756c756d204e6574776f726b20537461636b202d204c584d46204861736863617368204d6964737461746520546573742050726566697820363442"
    val expectedMidstateHex = "ae1cd45355c7b9063b467d57d0473f5d9313bf00880190a9505c03cf569d934a"

    // Block 1 (suffix "1234567890abcdef" + padding for 80-byte total message)
    val block1Hex = "31323334353637383930616263646566800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000280"
    val expectedFinalHash = "3b3f28f99ced03ad84ca9a6d7a05a64716f8a230b52e34664a1b0a6c4b6e5981"

    SimConfig.compile(Sha256Pipe(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.cmd.payload.block #= 0
      dut.io.cmd.payload.useMidstate #= false
      dut.io.cmd.payload.midstate #= 0
      dut.io.cmd.payload.tag #= 0
      dut.io.rsp.ready #= true
      dut.clockDomain.waitSampling()

      // Step 1: Compute Block 0 (prefix) with useMidstate = false
      dut.io.cmd.valid #= true
      dut.io.cmd.payload.block #= hexToBigInt(prefixBlockHex)
      dut.io.cmd.payload.useMidstate #= false
      dut.io.cmd.payload.midstate #= 0
      dut.io.cmd.payload.tag #= 0x111L
      dut.clockDomain.waitSampling()
      dut.io.cmd.valid #= false

      while (!dut.io.rsp.valid.toBoolean) {
        dut.clockDomain.waitSampling()
      }

      val computedMidstate = dut.io.rsp.payload.digest.toBigInt
      val computedMidstateHex = bigIntToHex64(computedMidstate)
      assert(
        computedMidstateHex == expectedMidstateHex,
        s"Midstate mismatch: got $computedMidstateHex, expected $expectedMidstateHex"
      )

      dut.clockDomain.waitSampling()

      // Step 2: Compute Block 1 (suffix) with useMidstate = true and midstate = computedMidstate
      dut.io.cmd.valid #= true
      dut.io.cmd.payload.block #= hexToBigInt(block1Hex)
      dut.io.cmd.payload.useMidstate #= true
      dut.io.cmd.payload.midstate #= computedMidstate
      dut.io.cmd.payload.tag #= 0x222L
      dut.clockDomain.waitSampling()
      dut.io.cmd.valid #= false

      while (!dut.io.rsp.valid.toBoolean) {
        dut.clockDomain.waitSampling()
      }

      val computedFinalHash = bigIntToHex64(dut.io.rsp.payload.digest.toBigInt)
      val computedTag = dut.io.rsp.payload.tag.toLong
      assert(
        computedFinalHash == expectedFinalHash,
        s"2-block midstate hash mismatch: got $computedFinalHash, expected $expectedFinalHash"
      )
      assert(computedTag == 0x222L, s"Tag mismatch: got 0x$computedTag%x")
    }
  }

  test("Sha256Pipe: continuous back-to-back streaming throughput (1 hash/cycle)") {
    val numBlocks = 16
    val abcBlock = hexToBigInt("61626380000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000018")
    val expectedHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    SimConfig.compile(Sha256Pipe(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.rsp.ready #= true
      dut.clockDomain.waitSampling()

      // Stream numBlocks consecutively, each with tag = i
      var outputsReceived = 0

      // Stimulus process
      fork {
        for (i <- 0 until numBlocks) {
          dut.io.cmd.valid #= true
          dut.io.cmd.payload.block #= abcBlock
          dut.io.cmd.payload.useMidstate #= false
          dut.io.cmd.payload.midstate #= 0
          dut.io.cmd.payload.tag #= i
          dut.clockDomain.waitSampling()
        }
        dut.io.cmd.valid #= false
      }

      // Collect outputs: verify back-to-back receipt without bubbles
      var lastCycleWasValid = false
      var timeout = 0
      while (outputsReceived < numBlocks && timeout < 200) {
        dut.clockDomain.waitSampling()
        timeout += 1

        if (dut.io.rsp.valid.toBoolean) {
          val hash = bigIntToHex64(dut.io.rsp.payload.digest.toBigInt)
          val tag = dut.io.rsp.payload.tag.toLong
          assert(hash == expectedHash, s"Output $outputsReceived bad hash: $hash")
          assert(tag == outputsReceived, s"Output $outputsReceived bad tag: got $tag, expected $outputsReceived")

          if (outputsReceived > 0) {
            // Must have arrived on consecutive cycles (no bubbles)
            assert(lastCycleWasValid, s"Bubble detected between output ${outputsReceived - 1} and $outputsReceived!")
          }
          outputsReceived += 1
          lastCycleWasValid = true
        } else {
          lastCycleWasValid = false
        }
      }

      assert(outputsReceived == numBlocks, s"Expected $numBlocks outputs, only received $outputsReceived")
    }
  }

  test("Sha256Pipe: downstream backpressure stall verification") {
    val abcBlock = hexToBigInt("61626380000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000018")
    val expectedHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    SimConfig.compile(Sha256Pipe(roundsPerStage = 1)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.rsp.ready #= false // Start stalled!
      dut.clockDomain.waitSampling(5)

      // Feed one block into the pipeline
      dut.io.cmd.valid #= true
      dut.io.cmd.payload.block #= abcBlock
      dut.io.cmd.payload.useMidstate #= false
      dut.io.cmd.payload.midstate #= 0
      dut.io.cmd.payload.tag #= 0x42L
      dut.clockDomain.waitSampling()
      dut.io.cmd.valid #= false

      // Let pipeline run until output is valid (with ready still FALSE)
      var cycles = 0
      while (!dut.io.rsp.valid.toBoolean && cycles < 100) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      assert(dut.io.rsp.valid.toBoolean, "Output never arrived at rsp stage")

      // Hold ready low for 10 cycles; output MUST hold value and ready backpressure must be asserted
      for (_ <- 0 until 10) {
        dut.clockDomain.waitSampling()
        assert(dut.io.rsp.valid.toBoolean, "rsp.valid dropped while stalled!")
        assert(bigIntToHex64(dut.io.rsp.payload.digest.toBigInt) == expectedHash, "digest corrupted while stalled!")
        assert(!dut.io.cmd.ready.toBoolean, "cmd.ready should be false when pipeline is stalled!")
      }

      // Now release backpressure: verify handshake occurs, then on subsequent cycle output is consumed
      dut.io.rsp.ready #= true
      dut.clockDomain.waitSampling()
      assert(dut.io.rsp.valid.toBoolean, "Valid dropped prematurely before handshake cycle")

      // Subsequent cycle: output has been consumed, valid returns to false
      dut.clockDomain.waitSampling()
      assert(!dut.io.rsp.valid.toBoolean, s"Output was not consumed after handshake (valid=${dut.io.rsp.valid.toBoolean})")
    }
  }

  test("Sha256Pipe: configurable pipeline depth (roundsPerStage = 2 and 4)") {
    val abcBlock = hexToBigInt("61626380000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000018")
    val expectedHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    // Test 32 stages (2 rounds per stage, latency = 32 cycles)
    SimConfig.compile(Sha256Pipe(roundsPerStage = 2)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.rsp.ready #= true
      dut.clockDomain.waitSampling(5)

      dut.io.cmd.valid #= true
      dut.io.cmd.payload.block #= abcBlock
      dut.io.cmd.payload.useMidstate #= false
      dut.io.cmd.payload.midstate #= 0
      dut.io.cmd.payload.tag #= 0x32L
      dut.clockDomain.waitSampling()
      dut.io.cmd.valid #= false

      var latency = 0
      while (!dut.io.rsp.valid.toBoolean && latency < 60) {
        dut.clockDomain.waitSampling()
        latency += 1
      }

      assert(latency == 32, s"Expected latency of 32 cycles for roundsPerStage=2, got $latency")
      assert(bigIntToHex64(dut.io.rsp.payload.digest.toBigInt) == expectedHash)
    }

    // Test 16 stages (4 rounds per stage, latency = 16 cycles)
    SimConfig.compile(Sha256Pipe(roundsPerStage = 4)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.rsp.ready #= true
      dut.clockDomain.waitSampling(5)

      dut.io.cmd.valid #= true
      dut.io.cmd.payload.block #= abcBlock
      dut.io.cmd.payload.useMidstate #= false
      dut.io.cmd.payload.midstate #= 0
      dut.io.cmd.payload.tag #= 0x16L
      dut.clockDomain.waitSampling()
      dut.io.cmd.valid #= false

      var latency = 0
      while (!dut.io.rsp.valid.toBoolean && latency < 30) {
        dut.clockDomain.waitSampling()
        latency += 1
      }

      assert(latency == 16, s"Expected latency of 16 cycles for roundsPerStage=4, got $latency")
      assert(bigIntToHex64(dut.io.rsp.payload.digest.toBigInt) == expectedHash)
    }
  }
}
