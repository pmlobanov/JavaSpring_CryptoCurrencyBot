variable "folder_id" {
  description = "ID папки из консоли Yandex Cloud"
  type        = string
}

variable "network_folder_id" {
  description = "ID папки, в которой находятся сеть и подсеть"
  type        = string
}

variable "zone" {
  description = "Зона размещения оборудования"
  type        = string
  default     = "ru-central1-a"
}

variable "ssh_key_path" {
  description = "Путь к публичному ключу для доступа в систему"
  type        = string
  default     = "~/.ssh/id_yc.pub"
}

variable "ssh_private_key_file" {
  description = "Путь к приватному ключу для SSH-подключения"
  type        = string
  default     = "~/.ssh/id_yc"
}

variable "vm_name" {
  description = "Имя виртуальной машины"
  type        = string
  default     = "lab5-cryptobot"
}

variable "network_name" {
  description = "Имя VPC-сети"
  type        = string
  default     = "default"
}

variable "subnet_name" {
  description = "Имя подсети"
  type        = string
  default     = "default-ru-central1-a"
}

variable "remote_user" {
  description = "Пользователь для SSH и Ansible"
  type        = string
  default     = "ubuntu"
}
