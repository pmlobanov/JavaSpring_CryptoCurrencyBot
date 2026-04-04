# Виртуальная сеть для изоляции трафика
data "yandex_vpc_network" "net" {
  # Имя сети и каталог теперь приходят из infra env через Terraform variables
  name      = var.network_name
  folder_id = var.network_folder_id
}

# Подсеть с выделенным адресным пространством
data "yandex_vpc_subnet" "subnet" {
  # Используем параметры окружения вместо жёстко прошитых значений
  name      = var.subnet_name
  folder_id = var.network_folder_id
}

# поиск образа Ubuntu по семейству
data "yandex_compute_image" "ubuntu" {
  family = "ubuntu-2204-lts"
}

# Виртуальная машина с заданными вычислительными ресурсами
resource "yandex_compute_instance" "vm" {
  name        = var.vm_name
  platform_id = "standard-v3"
  zone        = var.zone

  # Параметры CPU и памяти ВМ
  resources {
    cores  = 2
    memory = 4
  }

  # Загрузочный диск с образом Ubuntu
  boot_disk {
    initialize_params {
      image_id = data.yandex_compute_image.ubuntu.id
      size     = 20
    }
  }

  # Подключение сервера к подсети и выдача внешнего IP-адреса
  network_interface {
    subnet_id = data.yandex_vpc_subnet.subnet.id
    nat       = true
  }

  # Передача публичного SSH-ключа внутрь сервера для входа без пароля
  metadata = {
    ssh-keys = "${var.remote_user}:${file(var.ssh_key_path)}"
  }
}

# Автоматическое создание inventory-файла для Ansible
resource "local_file" "ansible_inv" {
  content = templatefile("${path.module}/../ansible/hosts.yml.tpl", {
    # Передаём адрес и SSH-параметры в шаблон inventory
    vm_ip                    = yandex_compute_instance.vm.network_interface[0].nat_ip_address
    ansible_user             = var.remote_user
    ansible_private_key_file = var.ssh_private_key_file
  })
  filename   = "${path.module}/../ansible/hosts.yml"
  # Ждём, пока машина будет создана, прежде чем генерировать inventory
  depends_on = [yandex_compute_instance.vm]
}
