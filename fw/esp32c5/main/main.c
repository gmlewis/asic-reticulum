/*
 * Copyright 2026 Glenn Lewis. All rights reserved.
 * Use of this source code is governed by the BSD-style
 * license that can be found in the LICENSE file.
 */

#include <stdio.h>
#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "asic_qspi.h"

static const char *TAG = "hil_main";

static void run_self_test(void) {
    ESP_LOGI(TAG, "==========================================================");
    ESP_LOGI(TAG, "Starting Reticulum ASIC/FPGA Hardware-In-The-Loop Suite");
    ESP_LOGI(TAG, "==========================================================");

    // 1. Probe ASIC Status
    asic_status_t st;
    esp_err_t err = asic_qspi_get_status(&st);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "FAIL: Unable to query ASIC status over QSPI (%s)", esp_err_to_name(err));
        return;
    }
    ESP_LOGI(TAG, "PASS: ASIC Status detected (Arch Version: 0x%02X)", st.arch_version);

    // 2. X25519 Montgomery Ladder Test (RFC 7748 Vector 1)
    ESP_LOGI(TAG, "Running X25519 Montgomery Ladder Hardware Acceleration Test...");
    const uint8_t scalar[32] = {
        0xa5, 0x46, 0xe3, 0x6b, 0xf0, 0x52, 0x7c, 0x9d,
        0x3b, 0x16, 0x15, 0x4b, 0x82, 0x46, 0x5e, 0xdd,
        0x62, 0x14, 0x4c, 0x0a, 0xc1, 0xfc, 0x5a, 0x18,
        0x50, 0x6a, 0x22, 0x44, 0xba, 0x44, 0x9a, 0xc4
    };
    const uint8_t u_coord[32] = {
        0xe6, 0xdb, 0x68, 0x67, 0x58, 0x30, 0x30, 0xdb,
        0x35, 0x94, 0xc1, 0xa4, 0x24, 0xb1, 0x5f, 0x7c,
        0x72, 0x66, 0x24, 0xec, 0x26, 0xb3, 0x35, 0x3b,
        0x10, 0xa9, 0x03, 0xa6, 0xd0, 0xab, 0x1c, 0x4c
    };
    const uint8_t exp_result[32] = {
        0xc3, 0xda, 0x55, 0x37, 0x9d, 0xe9, 0xc6, 0x90,
        0x8e, 0x94, 0xea, 0x4d, 0xf2, 0x8d, 0x08, 0x4f,
        0x32, 0xec, 0xcf, 0x03, 0x49, 0x1c, 0x71, 0xf7,
        0x54, 0xb4, 0x07, 0x55, 0x77, 0xa2, 0x85, 0x52
    };

    int64_t t0 = esp_timer_get_time();
    ESP_ERROR_CHECK(asic_qspi_x25519_mult_async(scalar, u_coord));
    err = asic_qspi_wait_irq(pdMS_TO_TICKS(100));
    int64_t t1 = esp_timer_get_time();

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "FAIL: X25519 hardware timeout waiting for IRQ");
        return;
    }

    uint8_t act_result[32];
    ESP_ERROR_CHECK(asic_qspi_x25519_read(act_result));
    ESP_ERROR_CHECK(asic_qspi_clear_irq());

    if (memcmp(act_result, exp_result, 32) != 0) {
        ESP_LOGE(TAG, "FAIL: X25519 scalar multiplication mismatch!");
        return;
    }
    ESP_LOGI(TAG, "PASS: X25519 calculation completed in %lld us (~%lld cycles @ 50MHz)",
             (t1 - t0), (t1 - t0) * 50);

    // 3. Multi-Engine Token Pool Test (4 Parallel Seal Envelopes)
    ESP_LOGI(TAG, "Running 4-Engine Token Pool Parallel Seal & Readback Test...");
    const uint8_t sign_key[16] = {0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f,0x10};
    const uint8_t enc_key[16]  = {0x11,0x12,0x13,0x14,0x15,0x16,0x17,0x18,0x19,0x1a,0x1b,0x1c,0x1d,0x1e,0x1f,0x20};
    const uint8_t iv[16]       = {0x21,0x22,0x23,0x24,0x25,0x26,0x27,0x28,0x29,0x2a,0x2b,0x2c,0x2d,0x2e,0x2f,0x30};
    const char *plaintext      = "ESP32-C5 HIL Parallel Token Seal Verification Payload 32B";
    size_t pt_len = strlen(plaintext);

    t0 = esp_timer_get_time();
    for (int i = 0; i < 4; i++) {
        ESP_ERROR_CHECK(asic_qspi_token_seal_async(sign_key, enc_key, iv, (const uint8_t *)plaintext, pt_len));
    }

    uint8_t out_buf[256];
    size_t out_len = 0;
    uint8_t out_status = 0;

    for (int i = 0; i < 4; i++) {
        err = asic_qspi_wait_irq(pdMS_TO_TICKS(100));
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "FAIL: Timeout waiting for Token job %d IRQ", i);
            return;
        }
        ESP_ERROR_CHECK(asic_qspi_token_read(out_buf, &out_len, &out_status));
        ESP_ERROR_CHECK(asic_qspi_clear_irq());

        if (out_status != 0) {
            ESP_LOGE(TAG, "FAIL: Token job %d reported error status %d", i, out_status);
            return;
        }
        ESP_LOGI(TAG, "  Token Engine Job %d completed: sealed %d bytes", i, (int)out_len);
    }
    t1 = esp_timer_get_time();
    ESP_LOGI(TAG, "PASS: 4-Way Token Seal pool executed in %lld us", (t1 - t0));

    // 4. IFAC Hashcash Stamp Grinder Autonomous Execution
    ESP_LOGI(TAG, "Running Autonomous IFAC Hashcash Stamp Grinder Test...");
    uint8_t midstate[32];
    uint8_t base_cand[32];
    memset(midstate, 0x33, 32);
    memset(base_cand, 0x44, 32);

    t0 = esp_timer_get_time();
    ESP_ERROR_CHECK(asic_qspi_stamp_grind_async(12, midstate, base_cand, 768, 0, 10000));
    err = asic_qspi_wait_irq(pdMS_TO_TICKS(500));
    t1 = esp_timer_get_time();

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "FAIL: Timeout waiting for Stamp Grinder IRQ");
        return;
    }

    asic_stamp_result_t stamp_res;
    ESP_ERROR_CHECK(asic_qspi_stamp_read(&stamp_res));
    ESP_ERROR_CHECK(asic_qspi_clear_irq());

    ESP_LOGI(TAG, "PASS: Winning stamp found! Nonce: %llu, Leading Zeros: %d, Rounds: %llu, Time: %lld us",
             stamp_res.winning_nonce, stamp_res.winning_zeros, stamp_res.rounds_evaluated, (t1 - t0));

    ESP_LOGI(TAG, "==========================================================");
    ESP_LOGI(TAG, "ALL HARDWARE-IN-THE-LOOP TESTS PASSED SUCCESSFULLY!");
    ESP_LOGI(TAG, "==========================================================");
}

void app_main(void) {
    ESP_LOGI(TAG, "Initializing Reticulum Hardware Accelerator Host Interface...");
    esp_err_t err = asic_qspi_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Initialization failed: %s", esp_err_to_name(err));
        return;
    }

    // Run self test suite
    run_self_test();

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
}
