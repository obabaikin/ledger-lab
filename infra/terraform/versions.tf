terraform {
  required_version = ">= 1.16.0"

  required_providers {
    contabo = {
      source  = "contabo/contabo"
      version = "0.1.44"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "5.28.0"
    }
  }
}
