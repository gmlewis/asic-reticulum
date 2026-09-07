/*
 * Copyright 2026 Glenn Lewis. All rights reserved.
 * Use of this source code is governed by the BSD-style
 * license that can be found in the LICENSE file.
 */

#include "asic_qspi.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_rom_gpio.h"
#include "rom/ets_sys.h"
#include <string.h>

static const char *TAG = "asic_qspi";

static spi_device_handle_t s_spi_dev = NULL;
static SemaphoreHandle_t   s_irq_sem = NULL;
static SemaphoreHandle_t   s_bus_mux = NULL;

static void IRAM_ATTR asic_irq_isr_handler(void *arg) {
    BaseType_t xHigherPriorityTaskWoken = pdFALSE;
    if (s_irq_sem != NULL) {
        xSemaphoreGiveFromISR(s_irq_sem, &xHigherPriorityTaskWoken);
    }
    if (xHigherPriorityTaskWoken == pdTRUE) {
        portYIELD_FROM_ISR();
    }
}

static inline void cs_low(void) {
    gpio_set_level((gpio_num_t)CONFIG_ASIC_PIN_CS, 0);
    esp_rom_delay_us(1);
}

static inline void cs_high(void) {
    esp_rom_delay_us(1);
    gpio_set_level((gpio_num_t)CONFIG_ASIC_PIN_CS, 1);
    esp_rom_delay_us(1);
}

static esp_err_t qspi_tx_rx(const uint8_t *tx_buf, uint8_t *rx_buf, size_t len) {
    if (len == 0) return ESP_OK;

    spi_transaction_ext_t t;
    memset(&t, 0, sizeof(t));

    t.base.flags = SPI_TRANS_MODE_QIO;
    t.base.length = len * 8; // length in bits
    t.base.tx_buffer = tx_buf;
    t.base.rx_buffer = rx_buf;

    return spi_device_polling_transmit(s_spi_dev, (spi_transaction_t *)&t);
}

esp_err_t asic_qspi_init(void) {
    s_irq_sem = xSemaphoreCreateBinary();
    s_bus_mux = xSemaphoreCreateMutex();
    if (!s_irq_sem || !s_bus_mux) {
        ESP_LOGE(TAG, "Failed to create FreeRTOS sync primitives");
        return ESP_ERR_NO_MEM;
    }

    // Configure CS Pin as fast GPIO output
    gpio_config_t cs_conf = {
        .pin_bit_mask = (1ULL << CONFIG_ASIC_PIN_CS),
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&cs_conf);
    gpio_set_level((gpio_num_t)CONFIG_ASIC_PIN_CS, 1);

    // Configure IRQ Pin as input with pullup and falling-edge interrupt
    gpio_config_t irq_conf = {
        .pin_bit_mask = (1ULL << CONFIG_ASIC_PIN_IRQ),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_NEGEDGE,
    };
    gpio_config(&irq_conf);

    gpio_install_isr_service(0);
    gpio_isr_handler_add((gpio_num_t)CONFIG_ASIC_PIN_IRQ, asic_irq_isr_handler, NULL);

    // Configure Quad-SPI Master Bus (SPI2_HOST)
    spi_bus_config_t buscfg = {
        .data0_io_num = CONFIG_ASIC_PIN_D0,
        .data1_io_num = CONFIG_ASIC_PIN_D1,
        .data2_io_num = CONFIG_ASIC_PIN_D2,
        .data3_io_num = CONFIG_ASIC_PIN_D3,
        .sclk_io_num  = CONFIG_ASIC_PIN_SCLK,
        .max_transfer_sz = 4096,
        .flags = SPICOMMON_BUSFLAG_QUAD,
    };

    esp_err_t ret = spi_bus_initialize(SPI2_HOST, &buscfg, SPI_DMA_CH_AUTO);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "spi_bus_initialize failed: %s", esp_err_to_name(ret));
        return ret;
    }

    spi_device_interface_config_t devcfg = {
        .command_bits = 0,
        .address_bits = 0,
        .dummy_bits   = 0,
        .mode         = 0, // Mode 0 (CPOL=0, CPHA=0)
        .clock_speed_hz = 40 * 1000 * 1000, // 40 MHz
        .spics_io_num = -1, // Manual CS control for multi-phase packets
        .queue_size   = 1,
        .flags        = SPI_DEVICE_HALFDUPLEX,
    };

    ret = spi_bus_add_device(SPI2_HOST, &devcfg, &s_spi_dev);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "spi_bus_add_device failed: %s", esp_err_to_name(ret));
        return ret;
    }

    ESP_LOGI(TAG, "ASIC QSPI host driver initialized (40 MHz Quad-SPI, IRQ GPIO%d)", CONFIG_ASIC_PIN_IRQ);
    return ESP_OK;
}

static esp_err_t send_cmd(uint8_t opcode, const uint8_t *payload, size_t len) {
    uint8_t header[3];
    header[0] = opcode;
    header[1] = (uint8_t)((len >> 8) & 0xFF);
    header[2] = (uint8_t)(len & 0xFF);

    cs_low();
    esp_err_t ret = qspi_tx_rx(header, NULL, 3);
    if (ret == ESP_OK && len > 0 && payload != NULL) {
        ret = qspi_tx_rx(payload, NULL, len);
    }
    cs_high();
    return ret;
}

esp_err_t asic_qspi_get_status(asic_status_t *out_status) {
    if (!out_status) return ESP_ERR_INVALID_ARG;

    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    uint8_t cmd[3] = { ASIC_OP_STATUS, 0x00, 0x00 };
    uint8_t rx[4]  = { 0 };

    cs_low();
    esp_err_t ret = qspi_tx_rx(cmd, NULL, 3);
    if (ret == ESP_OK) {
        esp_rom_delay_us(1); // Pipeline turnaround
        ret = qspi_tx_rx(NULL, rx, 4);
    }
    cs_high();
    xSemaphoreGive(s_bus_mux);

    if (ret != ESP_OK) return ret;

    // Decode Byte 0
    out_status->token_irq          = (rx[0] & 0x80) != 0;
    out_status->x25519_irq         = (rx[0] & 0x40) != 0;
    out_status->x25519_busy        = (rx[0] & 0x20) != 0;
    out_status->x25519_done        = (rx[0] & 0x10) != 0;
    out_status->stamp_irq          = (rx[0] & 0x08) != 0;
    out_status->stamp_busy         = (rx[0] & 0x04) != 0;
    out_status->stamp_meets_target = (rx[0] & 0x02) != 0;
    out_status->stamp_done         = (rx[0] & 0x01) != 0;

    // Decode Byte 1
    out_status->token_busy   = (rx[1] & 0x02) != 0;
    out_status->token_done   = (rx[1] & 0x01) != 0;
    out_status->arch_version = (rx[1] >> 2);

    // Decode Bytes 2-3
    out_status->stamp_rounds_evaluated = ((uint16_t)rx[2] << 8) | rx[3];

    return ESP_OK;
}

esp_err_t asic_qspi_stamp_grind_async(uint8_t target_cost,
                                      const uint8_t midstate[32],
                                      const uint8_t base_candidate[32],
                                      uint64_t total_len_bits,
                                      uint64_t start_nonce,
                                      uint64_t max_rounds) {
    uint8_t payload[89];
    payload[0] = target_cost;
    memcpy(&payload[1], midstate, 32);
    memcpy(&payload[33], base_candidate, 32);

    for (int i = 0; i < 8; i++) {
        payload[65 + i] = (uint8_t)((total_len_bits >> (56 - i * 8)) & 0xFF);
        payload[73 + i] = (uint8_t)((start_nonce    >> (56 - i * 8)) & 0xFF);
        payload[81 + i] = (uint8_t)((max_rounds     >> (56 - i * 8)) & 0xFF);
    }

    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    esp_err_t ret = send_cmd(ASIC_OP_STAMP_GRIND, payload, sizeof(payload));
    xSemaphoreGive(s_bus_mux);
    return ret;
}

esp_err_t asic_qspi_stamp_read(asic_stamp_result_t *out_result) {
    if (!out_result) return ESP_ERR_INVALID_ARG;

    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    uint8_t cmd[3] = { ASIC_OP_STAMP_READ, 0x00, 0x00 };
    uint8_t rx[82] = { 0 };

    cs_low();
    esp_err_t ret = qspi_tx_rx(cmd, NULL, 3);
    if (ret == ESP_OK) {
        esp_rom_delay_us(1);
        ret = qspi_tx_rx(NULL, rx, sizeof(rx));
    }
    cs_high();
    xSemaphoreGive(s_bus_mux);

    if (ret != ESP_OK) return ret;

    out_result->status_byte   = rx[0];
    out_result->winning_zeros = rx[1];

    out_result->winning_nonce = 0;
    out_result->rounds_evaluated = 0;
    for (int i = 0; i < 8; i++) {
        out_result->winning_nonce    = (out_result->winning_nonce << 8) | rx[2 + i];
        out_result->rounds_evaluated = (out_result->rounds_evaluated << 8) | rx[10 + i];
    }

    memcpy(out_result->winning_digest,    &rx[18], 32);
    memcpy(out_result->winning_candidate, &rx[50], 32);
    return ESP_OK;
}

esp_err_t asic_qspi_x25519_mult_async(const uint8_t scalar[32],
                                      const uint8_t u_coord[32]) {
    uint8_t payload[64];
    memcpy(&payload[0], scalar, 32);
    memcpy(&payload[32], u_coord, 32);

    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    esp_err_t ret = send_cmd(ASIC_OP_X25519_MULT, payload, sizeof(payload));
    xSemaphoreGive(s_bus_mux);
    return ret;
}

esp_err_t asic_qspi_x25519_read(uint8_t out_result[32]) {
    if (!out_result) return ESP_ERR_INVALID_ARG;

    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    uint8_t cmd[3] = { ASIC_OP_X25519_READ, 0x00, 0x00 };

    cs_low();
    esp_err_t ret = qspi_tx_rx(cmd, NULL, 3);
    if (ret == ESP_OK) {
        esp_rom_delay_us(1);
        ret = qspi_tx_rx(NULL, out_result, 32);
    }
    cs_high();
    xSemaphoreGive(s_bus_mux);
    return ret;
}

esp_err_t asic_qspi_token_seal_async(const uint8_t sign_key[16],
                                     const uint8_t enc_key[16],
                                     const uint8_t iv[16],
                                     const uint8_t *plaintext,
                                     size_t pt_len) {
    if (!sign_key || !enc_key || !iv || !plaintext) return ESP_ERR_INVALID_ARG;

    size_t payload_len = 48 + pt_len;
    uint8_t *payload = malloc(payload_len);
    if (!payload) return ESP_ERR_NO_MEM;

    memcpy(&payload[0], sign_key, 16);
    memcpy(&payload[16], enc_key, 16);
    memcpy(&payload[32], iv, 16);
    memcpy(&payload[48], plaintext, pt_len);

    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    esp_err_t ret = send_cmd(ASIC_OP_TOKEN_SEAL, payload, payload_len);
    xSemaphoreGive(s_bus_mux);

    free(payload);
    return ret;
}

esp_err_t asic_qspi_token_open_async(const uint8_t sign_key[16],
                                     const uint8_t enc_key[16],
                                     const uint8_t iv[16],
                                     const uint8_t *ciphertext,
                                     size_t ct_len) {
    if (!sign_key || !enc_key || !iv || !ciphertext) return ESP_ERR_INVALID_ARG;

    size_t payload_len = 48 + ct_len;
    uint8_t *payload = malloc(payload_len);
    if (!payload) return ESP_ERR_NO_MEM;

    memcpy(&payload[0], sign_key, 16);
    memcpy(&payload[16], enc_key, 16);
    memcpy(&payload[32], iv, 16);
    memcpy(&payload[48], ciphertext, ct_len);

    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    esp_err_t ret = send_cmd(ASIC_OP_TOKEN_OPEN, payload, payload_len);
    xSemaphoreGive(s_bus_mux);

    free(payload);
    return ret;
}

esp_err_t asic_qspi_token_read(uint8_t *out_buf,
                               size_t *out_len,
                               uint8_t *out_status) {
    if (!out_buf || !out_len || !out_status) return ESP_ERR_INVALID_ARG;

    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    uint8_t cmd[3] = { ASIC_OP_TOKEN_READ, 0x00, 0x00 };
    uint8_t header[3] = { 0 };

    cs_low();
    esp_err_t ret = qspi_tx_rx(cmd, NULL, 3);
    if (ret == ESP_OK) {
        esp_rom_delay_us(1);
        ret = qspi_tx_rx(NULL, header, 3);
    }

    if (ret != ESP_OK) {
        cs_high();
        xSemaphoreGive(s_bus_mux);
        return ret;
    }

    *out_status = header[0];
    size_t len  = ((size_t)header[1] << 8) | header[2];
    *out_len    = len;

    if (len > 0) {
        ret = qspi_tx_rx(NULL, out_buf, len);
    }
    cs_high();
    xSemaphoreGive(s_bus_mux);
    return ret;
}

esp_err_t asic_qspi_wait_irq(TickType_t timeout_ticks) {
    if (gpio_get_level((gpio_num_t)CONFIG_ASIC_PIN_IRQ) == 0) {
        return ESP_OK; // Line is already asserted active-low
    }
    if (xSemaphoreTake(s_irq_sem, timeout_ticks) == pdTRUE) {
        return ESP_OK;
    }
    return ESP_ERR_TIMEOUT;
}

esp_err_t asic_qspi_clear_irq(void) {
    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    esp_err_t ret = send_cmd(ASIC_OP_IRQ_CLEAR, NULL, 0);
    xSemaphoreGive(s_bus_mux);
    return ret;
}

esp_err_t asic_qspi_abort(void) {
    xSemaphoreTake(s_bus_mux, portMAX_DELAY);
    esp_err_t ret = send_cmd(ASIC_OP_ABORT, NULL, 0);
    xSemaphoreGive(s_bus_mux);
    return ret;
}
