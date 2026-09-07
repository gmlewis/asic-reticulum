package reticulum.crypto

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import scala.util.Random

class Field25519Test extends AnyFunSuite {
  val P: BigInt = (BigInt(1) << 255) - 19
  val A24: BigInt = 121665

  test("Field25519: modular addition unit tests") {
    SimConfig.compile {
      new Component {
        val a = in UInt (256 bits)
        val b = in UInt (256 bits)
        val res = out(Field25519.add(a, b))
      }
    }.doSim { dut =>
      def check(a: BigInt, b: BigInt): Unit = {
        dut.a #= a
        dut.b #= b
        sleep(1)
        val expected = (a + b) % P
        val actual = dut.res.toBigInt
        assert(actual == expected, f"Add mismatch for $a + $b: expected $expected%x, got $actual%x")
      }

      check(0, 0)
      check(P - 1, 1)
      check(P - 1, P - 1)
      check(123456789, 987654321)

      val rng = new Random(42)
      for (_ <- 0 until 50) {
        val a = BigInt(255, rng) % P
        val b = BigInt(255, rng) % P
        check(a, b)
      }
    }
  }

  test("Field25519: modular subtraction unit tests") {
    SimConfig.compile {
      new Component {
        val a = in UInt (256 bits)
        val b = in UInt (256 bits)
        val res = out(Field25519.sub(a, b))
      }
    }.doSim { dut =>
      def check(a: BigInt, b: BigInt): Unit = {
        dut.a #= a
        dut.b #= b
        sleep(1)
        val expected = (a - b + P) % P
        val actual = dut.res.toBigInt
        assert(actual == expected, f"Sub mismatch for $a - $b: expected $expected%x, got $actual%x")
      }

      check(0, 0)
      check(100, 100)
      check(0, 1)
      check(1, P - 1)
      check(P - 1, 0)

      val rng = new Random(43)
      for (_ <- 0 until 50) {
        val a = BigInt(255, rng) % P
        val b = BigInt(255, rng) % P
        check(a, b)
      }
    }
  }

  test("Field25519: 512-bit modular reduction against BigInt") {
    SimConfig.compile {
      new Component {
        val prod = in UInt (512 bits)
        val res = out(Field25519.reduce512(prod))
      }
    }.doSim { dut =>
      def check(prod: BigInt): Unit = {
        dut.prod #= prod
        sleep(1)
        val expected = prod % P
        val actual = dut.res.toBigInt
        assert(actual == expected, f"Reduction mismatch for $prod: expected $expected%x, got $actual%x")
      }

      check(0)
      check(1)
      check(P - 1)
      check(P)
      check(P + 1)
      check(BigInt(1) << 255)
      check((P - 1) * (P - 1))

      val rng = new Random(44)
      for (_ <- 0 until 100) {
        val prod = BigInt(510, rng)
        check(prod)
      }
    }
  }

  test("FieldMultiplier: 1-cycle registered multiplier verification") {
    SimConfig.withWave.compile(FieldMultiplier()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.a #= 0
      dut.io.b #= 0
      dut.clockDomain.waitSampling()

      def mulTest(a: BigInt, b: BigInt): Unit = {
        dut.io.a #= a
        dut.io.b #= b
        dut.io.start #= true
        dut.clockDomain.waitSampling()
        dut.io.start #= false
        dut.clockDomain.waitSamplingWhere(dut.io.valid.toBoolean)
        val expected = (a * b) % P
        val actual = dut.io.result.toBigInt
        assert(actual == expected, f"Mul mismatch: $a * $b expected $expected%x, got $actual%x")
      }

      // Edge cases
      mulTest(0, 0)
      mulTest(12345, 0)
      mulTest(12345, 1)
      mulTest(P - 1, 1)
      mulTest(P - 1, P - 1)
      mulTest(A24, P - 2)

      val rng = new Random(45)
      for (_ <- 0 until 30) {
        val a = BigInt(255, rng) % P
        val b = BigInt(255, rng) % P
        mulTest(a, b)
      }
    }
  }
}
