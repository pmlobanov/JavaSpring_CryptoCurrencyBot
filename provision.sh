#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_ENV_FILE="${INFRA_ENV_FILE:-$SCRIPT_DIR/.infra.env}"

set -a
source "$INFRA_ENV_FILE"
set +a

YC_TOKEN="$(yc iam create-token)"
export YC_TOKEN
export TF_VAR_folder_id="$FOLDER_ID"
export TF_VAR_network_folder_id="$NETWORK_FOLDER_ID"
export TF_VAR_zone="$ZONE"
export TF_VAR_vm_name="$VM_NAME"
export TF_VAR_network_name="$NETWORK_NAME"
export TF_VAR_subnet_name="$SUBNET_NAME"
export TF_VAR_remote_user="$REMOTE_USER"
export TF_VAR_ssh_key_path="$SSH_KEY_PATH"
export TF_VAR_ssh_private_key_file="$SSH_PRIVATE_KEY_FILE"

echo "Создание инфраструктуры..."
cd "$SCRIPT_DIR/terraform"
terraform init -upgrade
terraform apply -auto-approve
VM_IP="$(terraform output -raw vm_ip)"
cd "$SCRIPT_DIR"

echo "Ожидание готовности SSH..."
until ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 -i "$SSH_PRIVATE_KEY_FILE" "$REMOTE_USER@$VM_IP" echo "ready"; do
  sleep 5
done

echo "Настройка ПО..."
cd "$SCRIPT_DIR/ansible"
ansible-playbook playbook.yml -v
cd "$SCRIPT_DIR"

echo "Готово! IP: $VM_IP"
echo "SSH: ssh -i $SSH_PRIVATE_KEY_FILE $REMOTE_USER@$VM_IP"
