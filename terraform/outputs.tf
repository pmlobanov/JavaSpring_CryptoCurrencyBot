# Выводимые данные после применения конфигурации
# Публичный IPv4-адрес виртуальной машины
output "vm_ip" {
  description = "Внешний адрес для удалённого подключения"
  value       = yandex_compute_instance.vm.network_interface[0].nat_ip_address
}