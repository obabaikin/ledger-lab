# Credentials come from the environment: CNTB_OAUTH2_* for Contabo, CLOUDFLARE_API_TOKEN for Cloudflare.
provider "contabo" {}
provider "cloudflare" {}

locals {
  public_tcp_ports    = ["22", "80", "443"]
  cloudflare_auto_ttl = 1
  server_ipv4         = data.contabo_instance.server.ip_config[0].v4[0].ip
}

# Read only: the server is bought by hand, and this configuration must not be able to order or cancel one.
data "contabo_instance" "server" {
  id = tostring(var.instance_id)
}

resource "contabo_firewall" "server" {
  name         = "ledger-lab"
  description  = "SSH, HTTP and HTTPS in; everything else closed"
  status       = "active"
  instance_ids = [var.instance_id]

  rules {
    inbound {
      protocol   = "tcp"
      dest_ports = local.public_tcp_ports
      action     = "accept"
      status     = "active"

      src_cidr {
        ipv4 = ["0.0.0.0/0"]
        ipv6 = ["::/0"]
      }
    }
  }
}

data "cloudflare_zone" "domain" {
  filter = {
    name = var.domain
  }
}

resource "cloudflare_dns_record" "server" {
  zone_id = data.cloudflare_zone.domain.zone_id
  name    = var.domain
  type    = "A"
  content = local.server_ipv4
  ttl     = local.cloudflare_auto_ttl
  # Let's Encrypt's HTTP-01 challenge and the client IP for Traefik's rate limit need the server itself, not a proxy.
  proxied = false
}
