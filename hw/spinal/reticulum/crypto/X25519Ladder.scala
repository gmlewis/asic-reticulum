package reticulum.crypto

import spinal.core._
import spinal.lib._

/**
 * IO bundle for X25519 Montgomery Ladder Hardware Engine.
 *
 *  - scalar: 32-byte secret scalar in little-endian byte order.
 *  - uCoord: 32-byte u-coordinate base point in little-endian byte order.
 *  - result: 32-byte computed u-coordinate shared secret in little-endian order.
 */
case class X25519LadderIo() extends Bundle {
  val start    = in Bool()
  val abort    = in Bool()
  val irqClear = in Bool()
  val scalar   = in Bits(256 bits)
  val uCoord   = in Bits(256 bits)

  val busy   = out Bool()
  val done   = out Bool()
  val irq    = out Bool()
  val result = out Bits(256 bits)
}

object X25519State extends SpinalEnum {
  val IDLE, INIT, LADDER, FINAL_SWAP, INVERSION, FINAL_MUL, DONE = newElement()
}

/**
 * Constant-time X25519 Diffie-Hellman scalar multiplication core.
 *
 * Implements RFC 7748 Section 5.1 using the Montgomery ladder algorithm over
 * GF(2^255 - 19) in projective XZ coordinates:
 *  - 255 constant-time ladder iterations (bits 254 down to 0).
 *  - Bernstein's 254-squaring + 11-multiplication addition chain for modular inversion.
 *  - Total latency: ~4,350 clock cycles (~87 us @ 50 MHz).
 */
case class X25519Ladder() extends Component {
  val io = X25519LadderIo()

  val state = RegInit(X25519State.IDLE)

  // Projective coordinate registers
  val regX1 = Reg(UInt(256 bits)) init (0)
  val regX2 = Reg(UInt(256 bits)) init (0)
  val regZ2 = Reg(UInt(256 bits)) init (0)
  val regX3 = Reg(UInt(256 bits)) init (0)
  val regZ3 = Reg(UInt(256 bits)) init (0)

  // Scalar and loop tracking
  val regScalar = Reg(Bits(256 bits)) init (0)
  val regBit    = Reg(UInt(8 bits)) init (0)
  val regSwap   = Reg(Bool()) init (False)
  val regStep   = Reg(UInt(5 bits)) init (0)

  // Inversion addition chain tracking
  val regInvStep    = Reg(UInt(5 bits)) init (0)
  val regSqrCounter = Reg(UInt(8 bits)) init (0)

  // Arithmetic temporary registers
  val regA  = Reg(UInt(256 bits)) init (0)
  val regB  = Reg(UInt(256 bits)) init (0)
  val regC  = Reg(UInt(256 bits)) init (0)
  val regD  = Reg(UInt(256 bits)) init (0)
  val regAA = Reg(UInt(256 bits)) init (0)
  val regBB = Reg(UInt(256 bits)) init (0)
  val regE  = Reg(UInt(256 bits)) init (0)
  val regDA = Reg(UInt(256 bits)) init (0)
  val regCB = Reg(UInt(256 bits)) init (0)
  val regT0 = Reg(UInt(256 bits)) init (0)
  val regT1 = Reg(UInt(256 bits)) init (0)
  val regT2 = Reg(UInt(256 bits)) init (0)
  val regT3 = Reg(UInt(256 bits)) init (0)

  // Status flags and result
  val regBusy   = RegInit(False)
  val regDone   = RegInit(False)
  val regIrq    = RegInit(False)
  val regResult = Reg(Bits(256 bits)) init (0)

  // State Machine
  switch(state) {
    is(X25519State.IDLE) {
      when(io.start) {
        regBusy := True
        regDone := False
        state   := X25519State.INIT
      }
    }

    is(X25519State.INIT) {
      // RFC 7748 clamping:
      // scalar[0..2] = 0, scalar[255] = 0, scalar[254] = 1
      val kClamped = Cat(B"1'b0", B"1'b1", io.scalar(253 downto 3), B"3'b000")
      regScalar := kClamped

      // Base point masking: bit 255 = 0
      val uMasked = Cat(B"1'b0", io.uCoord(254 downto 0)).asUInt
      regX1 := uMasked
      regX2 := 1
      regZ2 := 0
      regX3 := uMasked
      regZ3 := 1

      regSwap := False
      regBit  := 254
      regStep := 0
      state   := X25519State.LADDER
    }

    is(X25519State.LADDER) {
      switch(regStep) {
        is(0) {
          // Bit selection and conditional swap
          val kt      = regScalar(regBit)
          val swapNow = regSwap ^ kt
          regSwap := kt
          when(swapNow) {
            regX2 := regX3
            regX3 := regX2
            regZ2 := regZ3
            regZ3 := regZ2
          }
          regStep := 1
        }
        is(1) {
          regA    := Field25519.add(regX2, regZ2)
          regB    := Field25519.sub(regX2, regZ2)
          regStep := 2
        }
        is(2) {
          regC    := Field25519.add(regX3, regZ3)
          regD    := Field25519.sub(regX3, regZ3)
          regStep := 3
        }
        is(3) {
          regDA   := Field25519.mul(regD, regA)
          regStep := 4
        }
        is(4) {
          regCB   := Field25519.mul(regC, regB)
          regStep := 5
        }
        is(5) {
          regAA   := Field25519.sqr(regA)
          regStep := 6
        }
        is(6) {
          regBB   := Field25519.sqr(regB)
          regStep := 7
        }
        is(7) {
          regE    := Field25519.sub(regAA, regBB)
          regStep := 8
        }
        is(8) {
          regX2   := Field25519.mul(regAA, regBB)
          regStep := 9
        }
        is(9) {
          regT1   := Field25519.mulA24(regE)
          regStep := 10
        }
        is(10) {
          val sumAA_a24E = Field25519.add(regAA, regT1)
          regZ2   := Field25519.mul(regE, sumAA_a24E)
          regStep := 11
        }
        is(11) {
          regT2   := Field25519.add(regDA, regCB) // DA + CB
          regT3   := Field25519.sub(regDA, regCB) // DA - CB
          regStep := 12
        }
        is(12) {
          regX3   := Field25519.sqr(regT2) // (DA + CB)^2
          regStep := 13
        }
        is(13) {
          regT3   := Field25519.sqr(regT3) // (DA - CB)^2
          regStep := 14
        }
        is(14) {
          regZ3   := Field25519.mul(regX1, regT3) // x1 * (DA - CB)^2
          regStep := 15
        }
        is(15) {
          when(regBit === 0) {
            state := X25519State.FINAL_SWAP
          } otherwise {
            regBit  := regBit - 1
            regStep := 0
          }
        }
      }
    }

    is(X25519State.FINAL_SWAP) {
      when(regSwap) {
        regX2 := regX3
        regX3 := regX2
        regZ2 := regZ3
        regZ3 := regZ2
      }
      regInvStep    := 0
      regSqrCounter := 0
      state         := X25519State.INVERSION
    }

    is(X25519State.INVERSION) {
      // Bernstein addition chain for z2^(2^255 - 21)
      switch(regInvStep) {
        is(0) { // t0 = sqr(z2, 1)
          regT0      := Field25519.sqr(regZ2)
          regInvStep := 1
        }
        is(1) { // t1 = sqr(t0, 2)
          when(regSqrCounter === 0) {
            regT1         := Field25519.sqr(regT0)
            regSqrCounter := 1
          } otherwise {
            regT1         := Field25519.sqr(regT1)
            regSqrCounter := 0
            regInvStep    := 2
          }
        }
        is(2) { // t1 = t1 * z2
          regT1      := Field25519.mul(regT1, regZ2)
          regInvStep := 3
        }
        is(3) { // t0 = t0 * t1 (holds z2^11)
          regT0      := Field25519.mul(regT0, regT1)
          regInvStep := 4
        }
        is(4) { // t2 = sqr(t0, 1)
          regT2      := Field25519.sqr(regT0)
          regInvStep := 5
        }
        is(5) { // t1 = t1 * t2 (holds z2^(2^5 - 1))
          regT1         := Field25519.mul(regT1, regT2)
          regInvStep    := 6
          regSqrCounter := 4 // 5 squarings: 4, 3, 2, 1, 0
        }
        is(6) { // t2 = sqr(t1, 5)
          val src = Mux(regSqrCounter === 4, regT1, regT2)
          regT2 := Field25519.sqr(src)
          when(regSqrCounter === 0) {
            regInvStep := 7
          } otherwise {
            regSqrCounter := regSqrCounter - 1
          }
        }
        is(7) { // t1 = t2 * t1 (holds z2^(2^10 - 1))
          regT1         := Field25519.mul(regT2, regT1)
          regInvStep    := 8
          regSqrCounter := 9 // 10 squarings: 9..0
        }
        is(8) { // t2 = sqr(t1, 10)
          val src = Mux(regSqrCounter === 9, regT1, regT2)
          regT2 := Field25519.sqr(src)
          when(regSqrCounter === 0) {
            regInvStep := 9
          } otherwise {
            regSqrCounter := regSqrCounter - 1
          }
        }
        is(9) { // t2 = t2 * t1 (holds z2^(2^20 - 1))
          regT2         := Field25519.mul(regT2, regT1)
          regInvStep    := 10
          regSqrCounter := 19 // 20 squarings: 19..0
        }
        is(10) { // t3 = sqr(t2, 20)
          val src = Mux(regSqrCounter === 19, regT2, regT3)
          regT3 := Field25519.sqr(src)
          when(regSqrCounter === 0) {
            regInvStep := 11
          } otherwise {
            regSqrCounter := regSqrCounter - 1
          }
        }
        is(11) { // t2 = t3 * t2 (holds z2^(2^40 - 1))
          regT2         := Field25519.mul(regT3, regT2)
          regInvStep    := 12
          regSqrCounter := 9 // 10 squarings: 9..0
        }
        is(12) { // t3 = sqr(t2, 10)
          val src = Mux(regSqrCounter === 9, regT2, regT3)
          regT3 := Field25519.sqr(src)
          when(regSqrCounter === 0) {
            regInvStep := 13
          } otherwise {
            regSqrCounter := regSqrCounter - 1
          }
        }
        is(13) { // t1 = t3 * t1 (holds z2^(2^50 - 1))
          regT1         := Field25519.mul(regT3, regT1)
          regInvStep    := 14
          regSqrCounter := 49 // 50 squarings: 49..0
        }
        is(14) { // t3 = sqr(t1, 50)
          val src = Mux(regSqrCounter === 49, regT1, regT3)
          regT3 := Field25519.sqr(src)
          when(regSqrCounter === 0) {
            regInvStep := 15
          } otherwise {
            regSqrCounter := regSqrCounter - 1
          }
        }
        is(15) { // t2 = t3 * t1 (holds z2^(2^100 - 1))
          regT2         := Field25519.mul(regT3, regT1)
          regInvStep    := 16
          regSqrCounter := 99 // 100 squarings: 99..0
        }
        is(16) { // t3 = sqr(t2, 100)
          val src = Mux(regSqrCounter === 99, regT2, regT3)
          regT3 := Field25519.sqr(src)
          when(regSqrCounter === 0) {
            regInvStep := 17
          } otherwise {
            regSqrCounter := regSqrCounter - 1
          }
        }
        is(17) { // t2 = t3 * t2 (holds z2^(2^200 - 1))
          regT2         := Field25519.mul(regT3, regT2)
          regInvStep    := 18
          regSqrCounter := 49 // 50 squarings: 49..0
        }
        is(18) { // t3 = sqr(t2, 50)
          val src = Mux(regSqrCounter === 49, regT2, regT3)
          regT3 := Field25519.sqr(src)
          when(regSqrCounter === 0) {
            regInvStep := 19
          } otherwise {
            regSqrCounter := regSqrCounter - 1
          }
        }
        is(19) { // t1 = t3 * t1 (holds z2^(2^250 - 1))
          regT1         := Field25519.mul(regT3, regT1)
          regInvStep    := 20
          regSqrCounter := 4 // 5 squarings: 4..0
        }
        is(20) { // t1 = sqr(t1, 5)
          regT1 := Field25519.sqr(regT1)
          when(regSqrCounter === 0) {
            regInvStep := 21
          } otherwise {
            regSqrCounter := regSqrCounter - 1
          }
        }
        is(21) { // invZ = t1 * t0
          regT1 := Field25519.mul(regT1, regT0)
          state := X25519State.FINAL_MUL
        }
      }
    }

    is(X25519State.FINAL_MUL) {
      val uOut = Field25519.mul(regX2, regT1)
      regResult := uOut.asBits
      regBusy   := False
      regDone   := True
      regIrq    := True
      state     := X25519State.DONE
    }

    is(X25519State.DONE) {
      when(io.start) {
        regDone := False
        regIrq  := False
        state   := X25519State.INIT
      }
    }
  }

  // Interrupt handling & Abort override
  when(io.irqClear) {
    regIrq := False
  }

  when(io.abort) {
    regBusy := False
    regDone := False
    state   := X25519State.IDLE
  }

  io.busy   := regBusy
  io.done   := regDone
  io.irq    := regIrq
  io.result := regResult
}

/**
 * Generates synthesis-ready Verilog for the X25519 Montgomery Ladder engine.
 * Run with: sbt "runMain reticulum.crypto.X25519LadderVerilog"
 */
object X25519LadderVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )
  config.generateVerilog(X25519Ladder())
}
