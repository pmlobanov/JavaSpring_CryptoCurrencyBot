#!/usr/bin/env bash
set -euo pipefail

# Основные пути проекта и список манифестов в том порядке,
# в котором зависимости удобнее поднимать.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env}"
KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"
MANIFEST_FILES=(
  "$SCRIPT_DIR/zookeeper.yaml"
  "$SCRIPT_DIR/kafka.yaml"
  "$SCRIPT_DIR/mongodb.yaml"
  "$SCRIPT_DIR/vault.yaml"
  "$SCRIPT_DIR/app.yaml"
)

TMP_CONFIG_ENV="$SCRIPT_DIR/.k8s.config.env"
TMP_SECRET_ENV="$SCRIPT_DIR/.k8s.secret.env"
TMP_VAULT_INIT_ENV="$SCRIPT_DIR/.k8s.vault-init.env"

# Без .env этот сценарий не имеет смысла, потому что все шаблоны завязаны на переменные.
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Env file not found: $ENV_FILE" >&2
  exit 1
fi

cd "$PROJECT_DIR"

# Экспортируем переменные из .env в текущую shell-сессию,
# чтобы их увидел envsubst и временные файлы ConfigMap/Secret.
set -a
source "$ENV_FILE"
set +a

# Проверяем только те переменные, без которых деплой точно не соберётся.
required_vars=(
  MONGO_INITDB_ROOT_USERNAME
  MONGO_INITDB_ROOT_PASSWORD
  MONGO_INITDB_DATABASE
  MONGO_PORT
  VAULT_TOKEN
  VAULT_PORT
  SERVER_PORT
  K8S_SERVICE_PORT
  DOCKER_IMAGE
  K8S_APP_NAME
  K8S_DEPLOYMENT_NAME
  K8S_SERVICE_NAME
  KAFKA_PORT
  KAFKA_INTERNAL_PORT
  ZOOKEEPER_PORT
  TELEGRAM_BOT_TOKEN
  TELEGRAM_BOT_USERNAME
  BINGX_API_KEY
  BINGX_API_SECRET
)

for var_name in "${required_vars[@]}"; do
  if [[ -z "${!var_name:-}" ]]; then
    echo "Required variable is missing in $ENV_FILE: $var_name" >&2
    exit 1
  fi
done

# Временные файлы создаются на каждый запуск заново, поэтому удаляем их даже при ошибке.
cleanup() {
  rm -f "$TMP_CONFIG_ENV" "$TMP_SECRET_ENV" "$TMP_VAULT_INIT_ENV"
}

trap cleanup EXIT

# Удаляем всё, что было создано ранее, чтобы деплой был полностью с нуля
echo "=== Удаление старых ресурсов ==="

$KUBECTL_BIN delete deployment zookeeper kafka mongodb vault "$K8S_DEPLOYMENT_NAME" --ignore-not-found
$KUBECTL_BIN delete service zookeeper kafka mongodb vault "$K8S_SERVICE_NAME" --ignore-not-found
$KUBECTL_BIN delete pvc mongodb-data vault-data --ignore-not-found
$KUBECTL_BIN delete configmap crypto-bot-config mongo-init-script vault-init-script --ignore-not-found
$KUBECTL_BIN delete secret bot-secrets vault-init-env --ignore-not-found
$KUBECTL_BIN delete job vault-init --ignore-not-found
$KUBECTL_BIN delete pods --all --ignore-not-found 

echo "=== Старые ресурсы удалены ==="

# Из исходного .env раскладываем данные по ролям:
# обычный ConfigMap, основной Secret и отдельный mini-.env для vault-init Job.
cat > "$TMP_CONFIG_ENV" <<EOF
MONGO_INITDB_ROOT_USERNAME=$MONGO_INITDB_ROOT_USERNAME
MONGO_INITDB_DATABASE=$MONGO_INITDB_DATABASE
MONGO_PORT=$MONGO_PORT
VAULT_ADDR=http://vault:$VAULT_PORT
VAULT_PORT=$VAULT_PORT
SERVER_PORT=$SERVER_PORT
KAFKA_PORT=$KAFKA_PORT
KAFKA_INTERNAL_PORT=$KAFKA_INTERNAL_PORT
ZOOKEEPER_PORT=$ZOOKEEPER_PORT
TELEGRAM_BOT_USERNAME=$TELEGRAM_BOT_USERNAME
EOF

cat > "$TMP_SECRET_ENV" <<EOF
MONGO_INITDB_ROOT_PASSWORD=$MONGO_INITDB_ROOT_PASSWORD
VAULT_DEV_ROOT_TOKEN_ID=$VAULT_TOKEN
VAULT_TOKEN=$VAULT_TOKEN
TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN
BINGX_API_KEY=$BINGX_API_KEY
BINGX_API_SECRET=$BINGX_API_SECRET
EOF

# vault-init.sh ожидает файл .env, поэтому для него готовим отдельный урезанный вариант.
cat > "$TMP_VAULT_INIT_ENV" <<EOF
MONGO_INITDB_ROOT_USERNAME=$MONGO_INITDB_ROOT_USERNAME
MONGO_INITDB_ROOT_PASSWORD=$MONGO_INITDB_ROOT_PASSWORD
MONGO_INITDB_DATABASE=$MONGO_INITDB_DATABASE
MONGO_PORT=$MONGO_PORT
VAULT_TOKEN=$VAULT_TOKEN
VAULT_PORT=$VAULT_PORT
KAFKA_PORT=$KAFKA_PORT
KAFKA_INTERNAL_PORT=$KAFKA_INTERNAL_PORT
TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN
TELEGRAM_BOT_USERNAME=$TELEGRAM_BOT_USERNAME
BINGX_API_KEY=$BINGX_API_KEY
BINGX_API_SECRET=$BINGX_API_SECRET
EOF

# Создаём новые ресурсы (старые уже удалены)
$KUBECTL_BIN create configmap crypto-bot-config --from-env-file="$TMP_CONFIG_ENV"
$KUBECTL_BIN create secret generic bot-secrets --from-env-file="$TMP_SECRET_ENV"
$KUBECTL_BIN create configmap mongo-init-script --from-file="$PROJECT_DIR/mongo-init.js"
$KUBECTL_BIN create configmap vault-init-script --from-file="$PROJECT_DIR/vault-init.sh"
$KUBECTL_BIN create secret generic vault-init-env --from-file=.env="$TMP_VAULT_INIT_ENV"

# Каждый манифест прогоняем через envsubst отдельно.
# Так проще понимать, какой именно файл применился или сломался.
for manifest in "${MANIFEST_FILES[@]}"; do
  envsubst < "$manifest" | $KUBECTL_BIN apply -f -
done

# Сначала ждём инфраструктурные зависимости, потом Job и только в конце сам бот.
$KUBECTL_BIN wait --for=condition=available deployment/zookeeper --timeout=15m
$KUBECTL_BIN wait --for=condition=available deployment/mongodb --timeout=15m
$KUBECTL_BIN wait --for=condition=available deployment/vault --timeout=15m
$KUBECTL_BIN wait --for=condition=available deployment/kafka --timeout=15m
$KUBECTL_BIN wait --for=condition=complete job/vault-init --timeout=15m
$KUBECTL_BIN rollout status "deployment/$K8S_DEPLOYMENT_NAME" --timeout=15m

$KUBECTL_BIN get pods
$KUBECTL_BIN get svc "$K8S_SERVICE_NAME"