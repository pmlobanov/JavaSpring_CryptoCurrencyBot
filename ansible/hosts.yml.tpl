all:
  hosts:
    lab_vm:
      ansible_host: ${vm_ip}
      ansible_user: ${ansible_user}
      ansible_ssh_private_key_file: ${ansible_private_key_file}
