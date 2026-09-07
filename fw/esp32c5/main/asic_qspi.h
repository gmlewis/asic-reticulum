/*
 * Copyright 2026 Glenn Lewis. All rights reserved.
 * Use of this source code is governed by the BSD-style
 * license that can be found in the LICENSE file.
 */

#ifndef ASIC_QSPI_H
#define ASIC_QSPI_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "esp_err.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

#ifdef __cplusplus
extern "C" {
#endif

// ---------------------------------------------------------------------------
// Pinout Configurations (Matching Tang Primer 25K PMOD / ESP32-C5 GPIOs)
// ---------------------------------------------------------------------------
#ifndef CONFIG_ASIC_PIN_SCLK
#define CONFIG_ASIC_PIN_SCLK 18
#endif

#ifndef CONFIG_ASIC_PIN_CS
#define CONFIG_ASIC_PIN_CS   19
#endif

#ifndef CONFIG_ASIC_PIN_D0
#define CONFIG_ASIC_PIN_D0   20
#endif

#ifndef CONFIG_ASIC_PIN_D1
#define CONFIG_ASIC_PIN_D1   21
#endif

#ifndef CONFIG_ASIC_PIN_D2
#define CONFIG_ASIC_PIN_D2   22
#endif

#ifndef CONFIG_ASIC_PIN_D3
#define CONFIG_ASIC_PIN_D3   23
#endif

#ifndef CONFIG_ASIC_PIN_IRQ
#define CONFIG_ASIC_PIN_IRQ  9
#endif

// ---------------------------------------------------------------------------
// QSPI Protocol Opcodes
// ---------------------------------------------------------------------------
#define ASIC_OP_NOP          0x00
#define ASIC_OP_STATUS       0x01
#define ASIC_OP_ABORT        0x02
#define ASIC_OP_IRQ_CLEAR    0x03

#define ASIC_OP_STAMP_GRIND  0x10
#define ASIC_OP_STAMP_READ   0x11

#define ASIC_OP_X25519_MULT  0x20
#define ASIC_OP_X25519_READ  0x21

#define ASIC_OP_TOKEN_SEAL   0x30
#define ASIC_OP_TOKEN_OPEN   0x31
#define ASIC_OP_TOKEN_READ   0x32

// ---------------------------------------------------------------------------
// Data Structures
// ---------------------------------------------------------------------------

/**
 * Status snapshot returned by ASIC_OP_STATUS.
 */
typedef struct {
    // Byte 0 Flags
    bool stamp_done;
    bool stamp_meets_target;
    bool stamp_busy;
    bool stamp_irq;
    bool x25519_done;
    bool x25519_busy;
    bool x25519_irq;
    bool token_irq;

    // Byte 1 Flags
    bool token_done;
    bool token_busy;
    uint8_t arch_version;

    // Bytes 2-3
    uint16_t stamp_rounds_evaluated;
} asic_status_t;

/**
 * Result buffer returned by ASIC_OP_STAMP_READ.
 */
typedef struct {
    uint8_t  status_byte;
    uint8_t  winning_zeros;
    uint64_t winning_nonce;
    uint64_t rounds_evaluated;
    uint8_t  winning_digest[32];
    uint8_t  winning_candidate[32];
} asic_stamp_result_t;

// ---------------------------------------------------------------------------
// Driver API
// ---------------------------------------------------------------------------

/**
 * Initializes the ESP32-C5 GP-SPI master peripheral (SPI2_HOST) with GDMA
 * and configures the active-low hardware interrupt on CONFIG_ASIC_PIN_IRQ.
 */
esp_err_t asic_qspi_init(void);

/**
 * Queries the current execution status and flags of all on-chip crypto engines.
 */
esp_err_t asic_qspi_get_status(asic_status_t *out_status);

/**
 * Dispatches an IFAC Hashcash Stamp grinding job to the Stamper coprocessor.
 * Returns immediately while hardware grinds autonomously in the background.
 */
esp_err_t asic_qspi_stamp_grind_async(uint8_t target_cost,
                                      const uint8_t midstate[32],
                                      const uint8_t base_candidate[32],
                                      uint64_t total_len_bits,
                                      uint64_t start_nonce,
                                      uint64_t max_rounds);

/**
 * Reads back the winning stamp result (82 bytes) from the Stamper coprocessor.
 */
esp_err_t asic_qspi_stamp_read(asic_stamp_result_t *out_result);

/**
 * Dispatches an X25519 scalar multiplication job to the Montgomery ladder engine.
 * Computes u_out = scalar * u_coord over Curve25519 in constant time.
 */
esp_err_t asic_qspi_x25519_mult_async(const uint8_t scalar[32],
                                      const uint8_t u_coord[32]);

/**
 * Reads back the 32-byte field element result from X25519Ladder.
 */
esp_err_t asic_qspi_x25519_read(uint8_t out_result[32]);

/**
 * Dispatches a Token Seal operation (AES-128-CBC encryption + HMAC-SHA256).
 * Encrypts and authenticates plaintext using 1 of N available TokenEngines.
 */
esp_err_t asic_qspi_token_seal_async(const uint8_t sign_key[16],
                                     const uint8_t enc_key[16],
                                     const uint8_t iv[16],
                                     const uint8_t *plaintext,
                                     size_t pt_len);

/**
 * Dispatches a Token Open operation (HMAC-SHA256 verification + AES-128-CBC decryption).
 * Verifies authenticity and decrypts ciphertext using 1 of N available TokenEngines.
 */
esp_err_t asic_qspi_token_open_async(const uint8_t sign_key[16],
                                     const uint8_t enc_key[16],
                                     const uint8_t iv[16],
                                     const uint8_t *ciphertext,
                                     size_t ct_len);

/**
 * Reads the next completed Token Seal or Open envelope in FIFO completion order.
 */
esp_err_t asic_qspi_token_read(uint8_t *out_buf,
                               size_t *out_len,
                               uint8_t *out_status);

/**
 * Blocks until the ASIC asserts its active-low hardware interrupt (IRQ_N).
 */
esp_err_t asic_qspi_wait_irq(TickType_t timeout_ticks);

/**
 * Clears pending hardware interrupts across all coprocessors and advances
 * the Token completion FIFO queue.
 */
esp_err_t asic_qspi_clear_irq(void);

/**
 * Immediately terminates active grinding or operations across all engines.
 */
esp_err_t asic_qspi_abort(void);

#ifdef __cplusplus
}
#endif

#endif // ASIC_QSPI_H
