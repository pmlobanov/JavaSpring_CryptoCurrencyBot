#!/bin/sh

source .env

# Ждать, пока Vault не станет доступен
echo "Проверка доступности Vault (http://vault:${VAULT_PORT})..."
export VAULT_ADDR="http://vault:${VAULT_PORT}"
export VAULT_TOKEN="${VAULT_TOKEN}"

until vault status > /dev/null 2>&1
do
    echo "Ожидание запуска Vault..."
    sleep 2
done

echo "Vault доступен!"

echo "Авторизация в Vault..."
vault login "$VAULT_TOKEN"
echo "Авторизация успешна!"

echo "Сохранение секретов в Vault..."
vault kv put secret/crypto-bot \
    telegram.bot.token="${TELEGRAM_BOT_TOKEN}" \
    telegram.bot.username="${TELEGRAM_BOT_USERNAME}" \
    kafka.bootstrap-servers="kafka:${KAFKA_INTERNAL_PORT}" \
    kafka.consumer.group-id="telegram-bot-group" \
    kafka.topics.incoming="telegram-incoming-messages" \
    kafka.topics.outgoing="telegram-outgoing-messages" \
    mongodb.connection-string="mongodb://appuser:apppassword@mongodb:${MONGO_PORT}/BitBotDB?authSource=BitBotDB" \
    mongodb.database="BitBotDB" \
    bingx.api.key="${BINGX_API_KEY}" \
    bingx.api.secret="${BINGX_API_SECRET}" \
    bingx.api.url="https://open-api.bingx.com" \
    currency.api.url="https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies"

echo "Секреты успешно сохранены в Vault!"
echo "Инициализация завершена, продолжаем запуск приложения..."