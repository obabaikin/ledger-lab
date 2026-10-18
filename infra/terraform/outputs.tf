output "server_ipv4" {
  value = local.server_ipv4
}

output "firewall_id" {
  value = contabo_firewall.server.id
}
